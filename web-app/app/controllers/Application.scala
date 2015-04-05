package controllers

import play.api.mvc._

object Application extends Controller {

  def index = Action {

    Ok(views.html.index("Stay tuned, opening soon...", List.empty))
  }

  def about = Action {
    Ok(views.html.about())
  }

}