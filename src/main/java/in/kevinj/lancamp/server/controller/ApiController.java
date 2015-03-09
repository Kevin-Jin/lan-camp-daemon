package in.kevinj.lancamp.server.controller;

import in.kevinj.lancamp.server.support.DatabaseUtil;
import in.kevinj.lancamp.server.support.UserAuth;

import org.vertx.java.core.Vertx;
import org.vertx.java.core.json.JsonArray;
import org.vertx.java.core.json.JsonObject;
import org.vertx.java.platform.Container;

import com.jetdrone.vertx.yoke.middleware.YokeRequest;
import com.jetdrone.vertx.yoke.middleware.YokeResponse;

public class ApiController {
	private final Vertx vertx;
	private final Container container;

	public ApiController(Vertx vertx, Container container) {
		this.vertx = vertx;
		this.container = container;
	}

	public void login(YokeRequest req) {
		req.expectMultiPart(false);
		JsonObject json = req.body();
		String username = json.getString("username");
		String password = json.getString("password");
		UserAuth.doLogin(vertx, container, req, username, password, error -> {
			YokeResponse resp = req.response().setContentType("application/json", "utf-8");
			if (error.isEmpty())
				resp.end(new JsonObject().putString("user", username));
			else
				resp.end(new JsonObject().putString("error", error));
		});
	}

	private void updateRecursive(YokeRequest req, int i, JsonArray apps, String prevError) {
		if (i == -1) {
			req.response().end(new JsonObject().putString("error", prevError));
			return;
		}
		if (i >= apps.size()) {
			req.response().end(new JsonObject().putString("status", "ok"));
			return;
		}

		JsonObject app = apps.get(i);
		if (!app.containsField("clicks"))
			app.putNumber("clicks", 0);
		if (!app.containsField("keys"))
			app.putNumber("keys", 0);
		if (!app.containsField("times"))
			app.putNumber("times", 0);

		JsonObject queryUpd = new JsonObject()
			.putNumber("_id", UserAuth.getCurrentUserId(req))
			.putString("apps.app", app.getString("app"))
		;
		JsonObject changesUpd = new JsonObject()
			.putObject("$inc", new JsonObject()
				.putNumber("apps.$.clicks", app.getNumber("clicks"))
				.putNumber("apps.$.keys", app.getNumber("keys"))
				.putNumber("apps.$.times", app.getNumber("times"))
			)
		;
		JsonObject queryUps = new JsonObject()
			.putNumber("_id", UserAuth.getCurrentUserId(req))
		;
		JsonObject changesUps = new JsonObject()
			.putObject("$push", new JsonObject()
				.putObject("apps", app)
			)
		;

		//first try to update the embedded document, if it exists
		DatabaseUtil.update(vertx, "user", queryUpd, changesUpd, error -> {
			container.logger().warn("Could not save stats", error);
			updateRecursive(req, -1, apps, error.getMessage());
		}, updateResp -> {
			if (updateResp.left.body().getNumber("number").intValue() == 0) {
				//document does not exist. next try to "upsert" the embedded document
				DatabaseUtil.update(vertx, "user", queryUps, changesUps, error -> {
					container.logger().warn("Could not save stats", error);
					updateRecursive(req, -1, apps, error.getMessage());
				}, updateResp2 -> {
					updateRecursive(req, i + 1, apps, "");
				});
			} else {
				updateRecursive(req, i + 1, apps, "");
			}
		});
	}

	public void update(YokeRequest req) {
		if (UserAuth.getCurrentUserId(req) == -1) {
			req.response().end(new JsonObject().putString("error", "Not logged in."));
			return;
		}
		JsonObject json = req.body();
		updateRecursive(req, 0, json.getArray("apps"), "");
	}
}
