package kmpwizard.api

import com.varabyte.kobweb.api.Api
import com.varabyte.kobweb.api.ApiContext
import com.varabyte.kobweb.api.http.HttpMethod
import com.varabyte.kobweb.api.http.bodyOf
import com.varabyte.kobweb.api.http.text
import kmpwizard.generator.ProjectGenerator
import kmpwizard.ProjectSpec
import kmpwizard.validate
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

/** POST /api/generate  (body: ProjectSpec JSON)  ->  application/zip */
@Api
suspend fun generate(ctx: ApiContext) {
    if (ctx.req.method != HttpMethod.POST) {
        ctx.res.status = 405
        return
    }
    val spec = try {
        json.decodeFromString<ProjectSpec>(ctx.req.body!!.text())
    } catch (e: Exception) {
        ctx.res.status = 400
        ctx.res.body = bodyOf("Malformed request")
        return
    }
    val errors = spec.validate()
    if (errors.isNotEmpty()) {
        ctx.res.status = 400
        ctx.res.body = bodyOf(errors.joinToString("\n"))
        return
    }
    ctx.res.body = bodyOf(ProjectGenerator().generate(spec), contentType = "application/zip")
}
