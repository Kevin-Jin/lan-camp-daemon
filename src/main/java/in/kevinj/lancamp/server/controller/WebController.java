package in.kevinj.lancamp.server.controller;

import in.kevinj.lancamp.server.WebRouter;
import in.kevinj.lancamp.server.support.DatabaseUtil;
import in.kevinj.lancamp.server.support.DetailedLocation;
import in.kevinj.lancamp.server.support.UserAuth;

import org.vertx.java.core.Handler;
import org.vertx.java.core.Vertx;
import org.vertx.java.core.json.JsonArray;
import org.vertx.java.core.json.JsonObject;
import org.vertx.java.platform.Container;

import com.jetdrone.vertx.yoke.middleware.YokeRequest;

public class WebController {
	private final Vertx vertx;
	private final Container container;

	public WebController(Vertx vertx, Container container) {
		this.vertx = vertx;
		this.container = container;
	}

	public void index(YokeRequest req) {
		req.put("pagename", "LanCamp");
		req.response().render("in.kevinj.lancamp.server.index.soy");
	}

	public void loginPage(YokeRequest req) {
		req.put("pagename", "LanCamp");
		req.response().render("in.kevinj.lancamp.server.login.soy");
	}

	public void login(YokeRequest req) {
		String username = req.getFormParameter("username");
		String password = req.getFormParameter("password");
		UserAuth.doLogin(vertx, container, req,  username, password, error -> {
			if (!error.isEmpty()) {
				req.put("error", error);
				loginPage(req);
			} else {
				req.response().redirect(WebRouter.Routes.WebController$index.path());
			}
		});
	}

	public void registerPage(YokeRequest req) {
		req.put("pagename", "LanCamp");
		req.response().render("in.kevinj.lancamp.server.register.soy");
	}

	public void register(YokeRequest req) {
		String username = req.getFormParameter("username");
		String password = req.getFormParameter("password");
		UserAuth.doRegister(vertx, container, req,  username, password, error -> {
			if (!error.isEmpty()) {
				req.put("error", error);
				registerPage(req);
			} else {
				req.response().redirect(WebRouter.Routes.WebController$index.path());
			}
		});
	}

	public void controlPanelPage(YokeRequest req) {
		DatabaseUtil.find(vertx, "user",
				(new JsonObject()
					.putNumber("_id", UserAuth.getCurrentUserId(req))
				), (new JsonObject()
					.putNumber("location", 1)
				), error -> {
			container.logger().warn("Could not get saved location", error);
			req.put("error", error.getMessage());
			req.response().render("in.kevinj.lancamp.server.controlpanel.soy");
				}, queryResp -> {
			String lat = "", lng = "";
			if (queryResp.right.hasNext()) {
				JsonObject point = queryResp.right.next().getValue("location");
				if (point != null && point.getString("type").equals("Point")) {
					JsonArray coordinates = point.getArray("coordinates");
					lat = coordinates.get(0).toString();
					lng = coordinates.get(1).toString();
				}
			}
			DetailedLocation.get(vertx, container, lat, lng, (Handler<DetailedLocation>) resp -> {
				req.put("pagename", "LanCamp");
				if (resp != null) {
					req.put("coords", resp.lat + "," + resp.lng);
					req.put("address", resp.formattedAddress != null ? resp.formattedAddress : "");
				} else {
					req.put("coords", "");
					req.put("address", "");
				}
				req.response().render("in.kevinj.lancamp.server.controlpanel.soy");
			});
		});
	}

	public void controlPanelSave(YokeRequest req) {
		if (req.getFormParameter("changepassword") != null) {
			
		}
		if (req.getFormParameter("changelocation") != null) {
			String address = req.getFormParameter("address");
			DetailedLocation.getByAddress(vertx, container, address, (Handler<DetailedLocation>) resp -> {
				if (resp == null || resp.lat == null || resp.lng == null) {
					req.put("error", "'" + address + "' is not a recognized address.");
					controlPanelPage(req);
				} else {
					DatabaseUtil.update(vertx, "user",
							(new JsonObject()
								.putNumber("_id", UserAuth.getCurrentUserId(req))
							), (new JsonObject()
								.putObject("$set", new JsonObject()
									.putObject("location", new JsonObject()
										.putString("type", "Point")
										.putArray("coordinates", new JsonArray()
											.addNumber(Double.parseDouble(resp.lat))
											.addNumber(Double.parseDouble(resp.lng))
										)
									)
								)
							), error -> {
						container.logger().warn("Could not save location", error);
						req.put("error", error.getMessage());
						controlPanelPage(req);
							}, updateResp -> {
						req.put("error", "Successfully updated your location.");
						controlPanelPage(req);
							}
					);
				}
			});
		}
	}

	public void logoff(YokeRequest req) {
		UserAuth.doLogoff(vertx, container, req, result -> {
			if ("".equals(result)) {
				//logoff succeeded
				req.response().redirect(WebRouter.Routes.WebController$index.path());
			} else {
				//login failed, show the login form again, with an error
				req.put("error", result);
				index(req);
			}
		});
	}
}
