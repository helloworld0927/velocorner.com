package controllers.auth

import jp.t2v.lab.play2.auth.AuthenticityToken
import play.api.mvc.{Cookie, DiscardingCookie, RequestHeader, Result}

class NewCookieTokenAccessor(
                              protected val cookieName: String = "PLAY2AUTH_SESS_ID",
                              protected val cookieSecureOption: Boolean = false,
                              protected val cookieHttpOnlyOption: Boolean = true,
                              protected val cookieDomainOption: Option[String] = None,
                              protected val cookiePathOption: String = "/",
                              protected val cookieMaxAge: Option[Int] = None
                            ) {

  def put(token: AuthenticityToken)(result: Result)(implicit request: RequestHeader): Result = {
    val c = Cookie(cookieName, token, cookieMaxAge, cookiePathOption, cookieDomainOption, cookieSecureOption, cookieHttpOnlyOption)
    result.withCookies(c)
  }

  def extract(request: RequestHeader): Option[AuthenticityToken] = {
    request.cookies.get(cookieName).map(_.value)
  }

  def delete(result: Result)(implicit request: RequestHeader): Result = {
    result.discardingCookies(DiscardingCookie(cookieName))
  }
}