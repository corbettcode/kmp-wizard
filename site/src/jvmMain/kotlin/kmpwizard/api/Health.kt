package kmpwizard.api

import com.varabyte.kobweb.api.Api
import com.varabyte.kobweb.api.ApiContext
import com.varabyte.kobweb.api.http.HttpMethod
import com.varabyte.kobweb.api.http.bodyOf

/**
 * GET /api/health  ->  200 "ok"
 *
 * Used by App Platform's health check (.do/app.yaml) and scripts/smoke-test.sh. Answering proves the server-side
 * API jar is loaded, which a static page like `/` does not.
 */
@Api
suspend fun health(ctx: ApiContext) {
    if (ctx.req.method != HttpMethod.GET) {
        ctx.res.status = 405
        return
    }
    ctx.res.body = bodyOf("ok", contentType = "text/plain")
}
