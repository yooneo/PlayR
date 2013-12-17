package twentysix.rest

import play.core.Router
import play.api.mvc.EssentialAction
import scala.runtime.AbstractPartialFunction
import play.api.mvc.RequestHeader
import play.api.mvc.Handler
import play.api.mvc.Controller
import play.api.Logger
import play.api.mvc.Controller

trait RestRouter[C <:Controller] extends Router.Routes {
  def controller: C

  protected var _prefix: String =""

  def setPrefix(newPrefix: String) = {
    _prefix = newPrefix
  }

  def prefix = _prefix
  def documentation= Nil

  trait RoutingHandler {
    def isDefined(requestHeader: RequestHeader): Boolean
    def apply[A <: RequestHeader, B>: Handler]( requestHeader: A, default: A => B): B
  }

  object DefaultRoutingHandler extends RoutingHandler {
    def isDefined(requestHeader: RequestHeader) = false
    def apply[A <: RequestHeader, B>: Handler]( requestHeader: A, default: A => B): B = default(requestHeader)
  }

  private val IdExpression = "^/([^/]+)/?$".r
  private val SubResourceExpression = "^(/([^/]+)/([^/]+)).*$".r

  abstract class IdRoutingHandler[Id, T <:IdentifiedResource[Id]] extends RoutingHandler {
    def resource: T
    def isDefined(requestHeader: RequestHeader) = {
      requestHeader.path.drop(_prefix.length()) match {
        case "" | "/" => true
        case IdExpression(id) => true
        case _ => false
      }
    }

    def apply[A <: RequestHeader, B>: Handler]( requestHeader: A, default: A => B): B = {
      requestHeader.path.drop(_prefix.length()) match {
        case "" | "/" => withoutId(requestHeader, default)
        case IdExpression(id) => resource.fromId(id) match {
          case Some(id) => withId(id, requestHeader, default)
          case _ => default(requestHeader)
        }
        case _ => default(requestHeader)
      }
    }
    def withId[A <: RequestHeader, B>: Handler]( id: Id, requestHeader: A, default: A => B): B
    def withoutId[A <: RequestHeader, B>: Handler](requestHeader: A, default: A => B): B = {
      default(requestHeader)
    }
  }

  class GetRoutingHandler[Id](val resource: ResourceRead[Id]) extends IdRoutingHandler[Id, ResourceRead[Id]] {
    def withId[A <: RequestHeader, B>: Handler]( id: Id, requestHeader: A, default: A => B) = { resource.get(id) }
    override def withoutId[A <: RequestHeader, B>: Handler](requestHeader: A, default: A => B) = { resource.list }
  }

  class PostRoutingHandler(val resource: ResourceAction) extends RoutingHandler() {
    def isDefined(requestHeader: RequestHeader) = true
    def apply[A <: RequestHeader, B>: Handler]( requestHeader: A, default: A => B): B = {
      requestHeader.path.drop(_prefix.length()) match {
        case "" | "/" => resource.post
        case _ => default(requestHeader)
      }
    }
  }

  class PutRoutingHandler[Id](val resource: ResourceOverwrite[Id]) extends IdRoutingHandler[Id, ResourceOverwrite[Id]] {
    def withId[A <: RequestHeader, B>: Handler]( id: Id, requestHeader: A, default: A => B) = { resource.put(id) }
  }

  class DeleteRoutingHandler[Id](val resource: ResourceUpdate[Id]) extends IdRoutingHandler[Id, ResourceUpdate[Id]] {
    def withId[A <: RequestHeader, B>: Handler]( id: Id, requestHeader: A, default: A => B) = { resource.delete(id) }
  }

  class PatchRoutingHandler[Id](val resource: ResourceUpdate[Id]) extends IdRoutingHandler[Id, ResourceUpdate[Id]] {
    def withId[A <: RequestHeader, B>: Handler]( id: Id, requestHeader: A, default: A => B) = { resource.update(id) }
  }

  class SubRoutingHandler[Id](val resource: SubResource[Id]) extends RoutingHandler {
    def isDefined(requestHeader: RequestHeader) = true
    def apply[A <: RequestHeader, B>: Handler]( requestHeader: A, default: A => B): B = {
      requestHeader.path.drop(_prefix.length()) match {
        case SubResourceExpression(subPrefix, sid, subResource) => {
          for {
            f <- resource.subResources.get(subResource)
            id <- resource.fromId(sid)
            res <- Router.Include {
              val router = new RestRouter[Controller]{ val controller = f(id) }
              router.setPrefix(requestHeader.path.take(_prefix.length()+subPrefix.length()))
              router
            }.unapply(requestHeader)
          } yield res
        }.getOrElse(default(requestHeader))

        case _ => default(requestHeader)
      }
    }
  }

  def findRoutingHandler(requestHeader: RequestHeader): RoutingHandler = {
      if(requestHeader.path.startsWith(_prefix)) {
        val path = requestHeader.path.drop(_prefix.length())
        val method = requestHeader.method

        (path, method, controller) match {
          case (SubResourceExpression(_, _, _), _, c:SubResource[_]) => new SubRoutingHandler(c)
          case (_, "GET", c:ResourceRead[_]) => new GetRoutingHandler(c)
          case (_, "POST", c:ResourceAction) => new PostRoutingHandler(c)
          case (_, "PUT", c: ResourceOverwrite[_]) => new PutRoutingHandler(c)
          case (_, "DELETE", c:ResourceUpdate[_]) => new DeleteRoutingHandler(c)
          case (_, "PATCH", c:ResourceUpdate[_]) => new PatchRoutingHandler(c)
          case _     => DefaultRoutingHandler
        }
      } else {
        DefaultRoutingHandler
      }
  }

  def routes = new AbstractPartialFunction[RequestHeader, Handler] {
    def isDefinedAt(requestHeader: RequestHeader): Boolean = {
      findRoutingHandler(requestHeader).isDefined(requestHeader)
    }

    override def applyOrElse[A <: RequestHeader, B>: Handler]( requestHeader: A, default: A => B) = {
      findRoutingHandler(requestHeader)(requestHeader, default)
    }
  }
}

