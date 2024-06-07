# Design

There are two types of requests: one is a request to Trino Gateway, and the
other is a request that needs to be forwarded to Trino.

## Request Forwarded to Trino
"There are some pre-defined URIs that will be forwarded to Trino. Additional
URIs that need to be forwarded are configurable using `extraWhitelistPaths`.

In order to support additional URIs that are only known at runtime, we use
`RouterPreMatchContainerRequestFilter` to process every request before the
actual resource matching occurs. If the requests URI matches, the request
will be forwarded to `RouteToBackendResource`.

Flow of request forwarding:
1. Determine which backend Trino to route to.
2. Prepare a request to be sent to Trino. Via header and X-Forwarded headers
   are being added. Most of the headers are being forwarded to Trino as is.
3. Depends on the request URI, some of them require special handling. For
   request which submit a new query, we need to retrieve the queryId from the
   response from Trino. Some requests to WebUI require setting a session
   cookie to ensure OIDC works. These are done by chaining asynchronous
   operations using Future.
4. The execution of request to Trino and the response to client are handled by
   `airlift.jaxrs.AsyncResponseHandler`.
