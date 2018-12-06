package com.prisma.api.server

import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.model._
import com.prisma.api.ApiDependencies
import com.prisma.api.import_export.{BulkExport, BulkImport}
import com.prisma.api.project.ProjectFetcher
import com.prisma.api.schema.APIErrors.AuthFailure
import com.prisma.api.schema._
import com.prisma.errors.{ErrorReporter, ProjectMetadata}
import com.prisma.jwt.Auth
import com.prisma.shared.models.Project
import com.prisma.utils.`try`.TryExtensions._
import play.api.libs.json._
import sangria.schema.Schema

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

case class RequestHandler(
    projectFetcher: ProjectFetcher,
    schemaBuilder: SchemaBuilder,
    graphQlRequestHandler: GraphQlRequestHandler,
    auth: Auth,
    log: Function[String, Unit]
)(implicit reporter: ErrorReporter, ec: ExecutionContext, apiDependencies: ApiDependencies) {

  def handleRawRequestForPublicApi(
      projectId: String,
      rawRequest: RawRequest
  ): Future[(StatusCode, JsValue)] = {
    handleRawRequestWithSchemaBuilder(projectId, rawRequest) { project =>
      schemaBuilder(project)
    }
  }

  def handleRawRequestForPrivateApi(projectId: String, rawRequest: RawRequest): Future[(StatusCode, JsValue)] = {
    handleRawRequestWithSchemaBuilder(projectId, rawRequest) { project =>
      PrivateSchemaBuilder(project)(apiDependencies).build()
    }
  }

  def handleRawRequestWithSchemaBuilder(projectId: String, rawRequest: RawRequest)(schemaBuilderFn: Project => Schema[ApiUserContext, Unit]) = {
    handleRawRequest(projectId, rawRequest) { project =>
      for {
        graphQlRequest <- rawRequest.toGraphQlRequest(project, schema = schemaBuilderFn(project)).toFuture
        result         <- handleGraphQlRequest(graphQlRequest)
      } yield result
    }.recoverWith {
      case e: InvalidGraphQlRequest => Future.successful(OK -> JsonErrorHelper.errorJson(rawRequest.id, e.underlying.getMessage))
    }
  }

  def handleRawRequestForImport(projectId: String, rawRequest: RawRequest): Future[(StatusCode, JsValue)] = {
    handleRawRequest(projectId, rawRequest) { project =>
      val importer = new BulkImport(project)
      importer.executeImport(rawRequest.json).map(x => (200, x))
    }
  }

  def handleRawRequestForExport(projectId: String, rawRequest: RawRequest): Future[(StatusCode, JsValue)] = {
    handleRawRequest(projectId, rawRequest) { project =>
      val resolver = apiDependencies.dataResolver(project)
      val exporter = new BulkExport(project)
      exporter.executeExport(resolver, rawRequest.json).map(x => (200, x))
    }
  }

  def handleRawRequest[T](
      projectId: String,
      rawRequest: RawRequest,
  )(
      fn: Project => Future[(StatusCode, T)]
  ): Future[(StatusCode, T)] = {
    for {
      project <- fetchProject(projectId)
      _       <- verifyAuth(project, rawRequest)
      result  <- fn(project)
    } yield result
  }

  def verifyAuth(project: Project, rawRequest: RawRequest): Future[Unit] = Future {
    if (project.secrets.nonEmpty) {
      val token = auth.extractToken(rawRequest.authorizationHeader)
      auth.verifyToken(token, project.secrets) match {
        case Success(_)   => ()
        case Failure(err) => throw AuthFailure()
      }
    }
  }

  def handleGraphQlRequest(graphQlRequest: GraphQlRequest): Future[(StatusCode, JsValue)] = {
    graphQlRequestHandler.handle(graphQlRequest)
  }

  def fetchProject(projectId: String): Future[Project] = {
    val result = projectFetcher.fetch(projectIdOrAlias = projectId)

    result.onComplete {
      case Failure(t) => reporter.report(t, ProjectMetadata(projectId))
      case _          =>
    }

    result map {
      case None         => throw APIErrors.ProjectNotFound(projectId)
      case Some(schema) => schema
    }
  }
}