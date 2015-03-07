package in.kevinj.lancamp.server.controller;

import in.kevinj.lancamp.server.WebRouter;
import in.kevinj.lancamp.server.support.UserAuth;

import org.vertx.java.core.Vertx;
import org.vertx.java.platform.Container;

import com.jetdrone.vertx.yoke.middleware.YokeRequest;

public class IndexController {
	private final Vertx vertx;
	private final Container container;

	public IndexController(Vertx vertx, Container container) {
		this.vertx = vertx;
		this.container = container;
	}

	public void index(YokeRequest req) {
		req.put("pagename", "LanCamp");
		req.response().render("in.kevinj.lancamp.server.index.soy");
	}

	public void logoff(YokeRequest req) {
		UserAuth.doLogoff(vertx, container, req, result -> {
			if ("".equals(result)) {
				//logoff succeeded
				req.response().redirect(WebRouter.Routes.IndexController$index.path());
			} else {
				//login failed, show the login form again, with an error
				req.put("error", result);
				index(req);
			}
		});
	}
}
