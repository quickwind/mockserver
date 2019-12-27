package org.mockserver.mock.action;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoopGroup;
import io.netty.util.AttributeKey;
import org.apache.commons.lang3.StringUtils;
import org.mockserver.client.NettyHttpClient;
import org.mockserver.client.SocketCommunicationException;
import org.mockserver.client.SocketConnectionException;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.filters.HopByHopHeaderFilter;
import org.mockserver.log.TimeService;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.HttpStateHandler;
import org.mockserver.model.*;
import org.mockserver.proxy.ProxyConfiguration;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.curl.HttpRequestToCurlSerializer;
import org.slf4j.event.Level;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.util.Set;

import static io.netty.handler.codec.http.HttpHeaderNames.HOST;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.configuration.ConfigurationProperties.enableCORSForAPI;
import static org.mockserver.configuration.ConfigurationProperties.enableCORSForAllResponses;
import static org.mockserver.cors.CORSHeaders.isPreflightRequest;
import static org.mockserver.log.model.LogEntry.LogMessageType.*;
import static org.mockserver.model.HttpResponse.notFoundResponse;
import static org.slf4j.event.Level.TRACE;

/**
 * @author jamesdbloom
 */
public class ActionHandler {

    public static final AttributeKey<InetSocketAddress> REMOTE_SOCKET = AttributeKey.valueOf("REMOTE_SOCKET");

    private final HttpStateHandler httpStateHandler;
    private final Scheduler scheduler;
    private MockServerLogger mockServerLogger;
    private HttpResponseActionHandler httpResponseActionHandler;
    private HttpResponseTemplateActionHandler httpResponseTemplateActionHandler;
    private HttpResponseClassCallbackActionHandler httpResponseClassCallbackActionHandler;
    private HttpResponseObjectCallbackActionHandler httpResponseObjectCallbackActionHandler;
    private HttpForwardActionHandler httpForwardActionHandler;
    private HttpForwardTemplateActionHandler httpForwardTemplateActionHandler;
    private HttpForwardClassCallbackActionHandler httpForwardClassCallbackActionHandler;
    private HttpForwardObjectCallbackActionHandler httpForwardObjectCallbackActionHandler;
    private HttpOverrideForwardedRequestActionHandler httpOverrideForwardedRequestCallbackActionHandler;
    private HttpErrorActionHandler httpErrorActionHandler;

    // forwarding
    private NettyHttpClient httpClient;
    private HopByHopHeaderFilter hopByHopHeaderFilter = new HopByHopHeaderFilter();
    private HttpRequestToCurlSerializer httpRequestToCurlSerializer = new HttpRequestToCurlSerializer(mockServerLogger);

    public ActionHandler(EventLoopGroup eventLoopGroup, HttpStateHandler httpStateHandler, ProxyConfiguration proxyConfiguration) {
        this.httpStateHandler = httpStateHandler;
        this.scheduler = httpStateHandler.getScheduler();
        this.mockServerLogger = httpStateHandler.getMockServerLogger();
        this.httpClient = new NettyHttpClient(mockServerLogger, eventLoopGroup, proxyConfiguration);
    }

    public void processAction(final HttpRequest request, final ResponseWriter responseWriter, final ChannelHandlerContext ctx, Set<String> localAddresses, boolean proxyingRequest, final boolean synchronous) {
        if (request.getHeaders() == null || !request.getHeaders().containsEntry(httpStateHandler.getUniqueLoopPreventionHeaderName(), httpStateHandler.getUniqueLoopPreventionHeaderValue())) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(RECEIVED_REQUEST)
                    .setLogLevel(Level.INFO)
                    .setHttpRequest(request)
                    .setMessageFormat("received request:{}")
                    .setArguments(request)
            );
        }
        final Expectation expectation = httpStateHandler.firstMatchingExpectation(request);
        final boolean potentiallyHttpProxy = !StringUtils.isEmpty(request.getFirstHeader(HOST.toString())) && !localAddresses.contains(request.getFirstHeader(HOST.toString()));

        if (expectation != null && expectation.getAction() != null) {

            final Action action = expectation.getAction();
            switch (action.getType()) {
                case RESPONSE: {
                    scheduler.submit(() -> {
                        handleAnyException(request, responseWriter, synchronous, action, () -> {
                            final HttpResponse response = getHttpResponseActionHandler().handle((HttpResponse) action);
                            writeResponseActionResponse(response, responseWriter, request, action, synchronous);
                        });
                    }, synchronous);
                    break;
                }
                case RESPONSE_TEMPLATE: {
                    scheduler.submit(() -> {
                        handleAnyException(request, responseWriter, synchronous, action, () -> {
                            final HttpResponse response = getHttpResponseTemplateActionHandler().handle((HttpTemplate) action, request);
                            writeResponseActionResponse(response, responseWriter, request, action, synchronous);
                        });
                    }, synchronous);
                    break;
                }
                case RESPONSE_CLASS_CALLBACK: {
                    scheduler.submit(() -> {
                        handleAnyException(request, responseWriter, synchronous, action, () -> {
                            final HttpResponse response = getHttpResponseClassCallbackActionHandler().handle((HttpClassCallback) action, request);
                            writeResponseActionResponse(response, responseWriter, request, action, synchronous);
                        });
                    }, synchronous);
                    break;
                }
                case RESPONSE_OBJECT_CALLBACK: {
                    scheduler.submit(() -> getHttpResponseObjectCallbackActionHandler().handle(ActionHandler.this, (HttpObjectCallback) action, request, responseWriter, synchronous), synchronous);
                    break;
                }
                case FORWARD: {
                    scheduler.schedule(() -> {
                        handleAnyException(request, responseWriter, synchronous, action, () -> {
                            final HttpForwardActionResult responseFuture = getHttpForwardActionHandler().handle((HttpForward) action, request);
                            writeForwardActionResponse(responseFuture, responseWriter, request, action, synchronous);
                        });
                    }, synchronous, action.getDelay());
                    break;
                }
                case FORWARD_TEMPLATE: {
                    scheduler.schedule(() -> {
                        handleAnyException(request, responseWriter, synchronous, action, () -> {
                            final HttpForwardActionResult responseFuture = getHttpForwardTemplateActionHandler().handle((HttpTemplate) action, request);
                            writeForwardActionResponse(responseFuture, responseWriter, request, action, synchronous);
                        });
                    }, synchronous, action.getDelay());
                    break;
                }
                case FORWARD_CLASS_CALLBACK: {
                    scheduler.schedule(() -> {
                        handleAnyException(request, responseWriter, synchronous, action, () -> {
                            final HttpForwardActionResult responseFuture = getHttpForwardClassCallbackActionHandler().handle((HttpClassCallback) action, request);
                            writeForwardActionResponse(responseFuture, responseWriter, request, action, synchronous);
                        });
                    }, synchronous, action.getDelay());
                    break;
                }
                case FORWARD_OBJECT_CALLBACK: {
                    scheduler.schedule(() -> getHttpForwardObjectCallbackActionHandler().handle(ActionHandler.this, (HttpObjectCallback) action, request, responseWriter, synchronous), synchronous, action.getDelay());
                    break;
                }
                case FORWARD_REPLACE: {
                    scheduler.schedule(() -> {
                        handleAnyException(request, responseWriter, synchronous, action, () -> {
                            final HttpForwardActionResult responseFuture = getHttpOverrideForwardedRequestCallbackActionHandler().handle((HttpOverrideForwardedRequest) action, request);
                            writeForwardActionResponse(responseFuture, responseWriter, request, action, synchronous);
                        });
                    }, synchronous, action.getDelay());
                    break;
                }
                case ERROR: {
                    scheduler.schedule(() -> {
                        handleAnyException(request, responseWriter, synchronous, action, () -> {
                            getHttpErrorActionHandler().handle((HttpError) action, ctx);
                            mockServerLogger.logEvent(
                                new LogEntry()
                                    .setType(EXPECTATION_RESPONSE)
                                    .setLogLevel(Level.INFO)
                                    .setHttpRequest(request)
                                    .setHttpError((HttpError) action)
                                    .setMessageFormat("returning error:{}for request:{}for action:{}")
                                    .setArguments(action, request, action)
                            );
                        });
                    }, synchronous, action.getDelay());
                    break;
                }
            }

        } else if (isPreflightRequest(request) && (enableCORSForAPI() || enableCORSForAllResponses())) {

            responseWriter.writeResponse(request, OK);

        } else if (proxyingRequest || potentiallyHttpProxy) {

            if (request.getHeaders() != null && request.getHeaders().containsEntry(httpStateHandler.getUniqueLoopPreventionHeaderName(), httpStateHandler.getUniqueLoopPreventionHeaderValue())) {

                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(LogEntry.LogMessageType.TRACE)
                        .setLogLevel(TRACE)
                        .setMessageFormat("Received \"x-forwarded-by\" header caused by exploratory HTTP proxy or proxy loop - falling back to no proxy: {}")
                        .setArguments(request)
                );
                returnNotFound(responseWriter, request);

            } else {
                final long requestTime = TimeService.currentTimeMillis();
                final InetSocketAddress remoteAddress = ctx != null ? ctx.channel().attr(REMOTE_SOCKET).get() : null;
                HttpRequest tmpClonedRequest = hopByHopHeaderFilter.onRequest(request).withHeader(httpStateHandler.getUniqueLoopPreventionHeaderName(), httpStateHandler.getUniqueLoopPreventionHeaderValue());
                if (ConfigurationProperties.proxyHostHeader()) {
                    tmpClonedRequest.replaceHeader(new Header("Host", remoteAddress.getHostString() + ":" + remoteAddress.getPort()));
                }
                final HttpRequest clonedRequest = tmpClonedRequest;
                final HttpForwardActionResult responseFuture = new HttpForwardActionResult(clonedRequest, httpClient.sendRequest(clonedRequest, remoteAddress, potentiallyHttpProxy ? 1000 : ConfigurationProperties.socketConnectionTimeout()), null, remoteAddress);
                scheduler.submit(responseFuture, () -> {
                    try {
                        HttpResponse response = responseFuture.getHttpResponse().get();
                        if (response == null) {
                            response = notFoundResponse();
                        }
                        if (responseFuture.getOverriddenHttpResponse() != null) {
                            response.update(responseFuture.getOverriddenHttpResponse());
                        }
                        if (response.containsHeader(httpStateHandler.getUniqueLoopPreventionHeaderName(), httpStateHandler.getUniqueLoopPreventionHeaderValue())) {
                            response.removeHeader(httpStateHandler.getUniqueLoopPreventionHeaderName());
                            mockServerLogger.logEvent(
                                new LogEntry()
                                    .setType(EXPECTATION_NOT_MATCHED_RESPONSE)
                                    .setLogLevel(Level.INFO)
                                    .setHttpRequest(request)
                                    .setHttpResponse(notFoundResponse())
                                    .setMessageFormat("no expectation for:{}returning response:{}")
                                    .setArguments(request, notFoundResponse())
                            );
                        } else {
                            if (ConfigurationProperties.recordDelay()) {
                                response.withDelay(
                                        Delay.milliseconds(
                                                TimeService.currentTimeMillis() - requestTime));
                            }
                            if (response.getStatusCode() == 302 && ConfigurationProperties.proxyLocalRedirect()) {
                                String location = response.getFirstHeader("Location");
                                if (location.contains("//")) {
                                    location = location.split("//")[1];
                                    String newLocation = "/";
                                    if (location.contains("/")) {
                                        newLocation = location.substring(location.indexOf("/"));
                                    } 
                                    response.replaceHeader(new Header("Location", newLocation));
                                }
                            } 
                            mockServerLogger.logEvent(
                                new LogEntry()
                                    .setType(FORWARDED_REQUEST)
                                    .setLogLevel(Level.INFO)
                                    .setHttpRequest(request)
                                    .setHttpResponse(response)
                                    .setMessageFormat("returning response:{}for forwarded request" + NEW_LINE + NEW_LINE + " in json:{}" + NEW_LINE + NEW_LINE + " in curl:{}")
                                    .setArguments(response, request, httpRequestToCurlSerializer.toCurl(request, remoteAddress))
                            );
                        }
                        responseWriter.writeResponse(request, response, false);
                    } catch (SocketCommunicationException sce) {
                        returnNotFound(responseWriter, request);
                    } catch (Exception ex) {
                        if (potentiallyHttpProxy && (ex.getCause() instanceof ConnectException || ex.getCause() instanceof SocketConnectionException)) {
                            mockServerLogger.logEvent(
                                new LogEntry()
                                    .setType(LogEntry.LogMessageType.TRACE)
                                    .setLogLevel(TRACE)
                                    .setMessageFormat("Failed to connect to proxied socket due to exploratory HTTP proxy for: {}falling back to no proxy: {}")
                                    .setArguments(request, ex.getCause())
                            );
                            returnNotFound(responseWriter, request);
                        } else {
                            mockServerLogger.logEvent(
                                new LogEntry()
                                    .setType(EXCEPTION)
                                    .setLogLevel(Level.ERROR)
                                    .setHttpRequest(request)
                                    .setMessageFormat(ex.getMessage())
                                    .setThrowable(ex)
                            );
                        }
                    }
                }, synchronous);

            }

        } else {

            returnNotFound(responseWriter, request);

        }
    }

    private void handleAnyException(HttpRequest request, ResponseWriter responseWriter, boolean synchronous, Action action, Runnable processAction) {
        try {
            processAction.run();
        } catch (Throwable throwable) {
            writeResponseActionResponse(notFoundResponse(), responseWriter, request, action, synchronous);
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(WARN)
                    .setLogLevel(Level.INFO)
                    .setHttpRequest(request)
                    .setMessageFormat(throwable.getMessage())
                    .setThrowable(throwable)
            );
        }
    }

    void writeResponseActionResponse(final HttpResponse response, final ResponseWriter responseWriter, final HttpRequest request, final Action action, boolean synchronous) {
        scheduler.schedule(() -> {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(EXPECTATION_RESPONSE)
                    .setLogLevel(Level.INFO)
                    .setHttpRequest(request)
                    .setHttpResponse(response)
                    .setMessageFormat("returning response:{}for request:{}for action:{}")
                    .setArguments(response, request, action)
            );
            responseWriter.writeResponse(request, response, false);
        }, synchronous, action.getDelay(), response.getDelay());
    }

    void writeForwardActionResponse(final HttpForwardActionResult responseFuture, final ResponseWriter responseWriter, final HttpRequest request, final Action action, boolean synchronous) {
        scheduler.submit(responseFuture, () -> {
            try {
                HttpResponse response = responseFuture.getHttpResponse().get();
                if (responseFuture.getOverriddenHttpResponse() != null) {
                    response.update(responseFuture.getOverriddenHttpResponse());
                }
                responseWriter.writeResponse(request, response, false);
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(FORWARDED_REQUEST)
                        .setLogLevel(Level.INFO)
                        .setHttpRequest(request)
                        .setHttpResponse(response)
                        .setMessageFormat("returning response:{}for forwarded request\n\n in json:{}\n\n in curl:{}for action:{}")
                        .setArguments(response, responseFuture.getHttpRequest(), httpRequestToCurlSerializer.toCurl(responseFuture.getHttpRequest(), responseFuture.getRemoteAddress()), action)
                );
            } catch (Exception ex) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(EXCEPTION)
                        .setLogLevel(Level.ERROR)
                        .setHttpRequest(request)
                        .setMessageFormat(ex.getMessage())
                        .setThrowable(ex)
                );
            }
        }, synchronous);
    }

    private void returnNotFound(ResponseWriter responseWriter, HttpRequest request) {
        HttpResponse response = notFoundResponse();
        if (request.getHeaders().containsEntry(httpStateHandler.getUniqueLoopPreventionHeaderName(), httpStateHandler.getUniqueLoopPreventionHeaderValue())) {
            response.withHeader(httpStateHandler.getUniqueLoopPreventionHeaderName(), httpStateHandler.getUniqueLoopPreventionHeaderValue());
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(LogEntry.LogMessageType.TRACE)
                    .setLogLevel(TRACE)
                    .setHttpRequest(request)
                    .setMessageFormat("no expectation for:{}returning response:{}")
                    .setArguments(request, notFoundResponse())
            );
        } else {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(EXPECTATION_NOT_MATCHED_RESPONSE)
                    .setLogLevel(Level.INFO)
                    .setHttpRequest(request)
                    .setHttpResponse(notFoundResponse())
                    .setMessageFormat("no expectation for:{}returning response:{}")
                    .setArguments(request, notFoundResponse())
            );
        }
        responseWriter.writeResponse(request, response, false);
    }

    private HttpResponseActionHandler getHttpResponseActionHandler() {
        if (httpResponseActionHandler == null) {
            httpResponseActionHandler = new HttpResponseActionHandler();
        }
        return httpResponseActionHandler;
    }

    private HttpResponseTemplateActionHandler getHttpResponseTemplateActionHandler() {
        if (httpResponseTemplateActionHandler == null) {
            httpResponseTemplateActionHandler = new HttpResponseTemplateActionHandler(mockServerLogger);
        }
        return httpResponseTemplateActionHandler;
    }

    private HttpResponseClassCallbackActionHandler getHttpResponseClassCallbackActionHandler() {
        if (httpResponseClassCallbackActionHandler == null) {
            httpResponseClassCallbackActionHandler = new HttpResponseClassCallbackActionHandler(mockServerLogger);
        }
        return httpResponseClassCallbackActionHandler;
    }

    private HttpResponseObjectCallbackActionHandler getHttpResponseObjectCallbackActionHandler() {
        if (httpResponseObjectCallbackActionHandler == null) {
            httpResponseObjectCallbackActionHandler = new HttpResponseObjectCallbackActionHandler(httpStateHandler);
        }
        return httpResponseObjectCallbackActionHandler;
    }

    private HttpForwardActionHandler getHttpForwardActionHandler() {
        if (httpForwardActionHandler == null) {
            httpForwardActionHandler = new HttpForwardActionHandler(mockServerLogger, httpClient);
        }
        return httpForwardActionHandler;
    }

    private HttpForwardTemplateActionHandler getHttpForwardTemplateActionHandler() {
        if (httpForwardTemplateActionHandler == null) {
            httpForwardTemplateActionHandler = new HttpForwardTemplateActionHandler(mockServerLogger, httpClient);
        }
        return httpForwardTemplateActionHandler;
    }

    private HttpForwardClassCallbackActionHandler getHttpForwardClassCallbackActionHandler() {
        if (httpForwardClassCallbackActionHandler == null) {
            httpForwardClassCallbackActionHandler = new HttpForwardClassCallbackActionHandler(mockServerLogger, httpClient);
        }
        return httpForwardClassCallbackActionHandler;
    }

    private HttpForwardObjectCallbackActionHandler getHttpForwardObjectCallbackActionHandler() {
        if (httpForwardObjectCallbackActionHandler == null) {
            httpForwardObjectCallbackActionHandler = new HttpForwardObjectCallbackActionHandler(httpStateHandler, httpClient);
        }
        return httpForwardObjectCallbackActionHandler;
    }

    private HttpOverrideForwardedRequestActionHandler getHttpOverrideForwardedRequestCallbackActionHandler() {
        if (httpOverrideForwardedRequestCallbackActionHandler == null) {
            httpOverrideForwardedRequestCallbackActionHandler = new HttpOverrideForwardedRequestActionHandler(mockServerLogger, httpClient);
        }
        return httpOverrideForwardedRequestCallbackActionHandler;
    }

    private HttpErrorActionHandler getHttpErrorActionHandler() {
        if (httpErrorActionHandler == null) {
            httpErrorActionHandler = new HttpErrorActionHandler();
        }
        return httpErrorActionHandler;
    }

}
