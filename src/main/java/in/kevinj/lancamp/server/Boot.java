package in.kevinj.lancamp.server;

import in.kevinj.lancamp.server.support.BCryptWorker;
import in.kevinj.lancamp.server.templating.CompilerVerticle;

import java.util.UUID;

import org.vertx.java.core.json.JsonObject;
import org.vertx.java.platform.Verticle;

public class Boot extends Verticle {
	public static final String DB_HANDLE = "mongodb-persistor";
	public static final String BC_HANDLE = "bcrypt";

	@Override
	public void start() {
		final String instanceId = UUID.randomUUID().toString();

		JsonObject config = new JsonObject();
		config.putString("address", DB_HANDLE);
		config.putString("host", "localhost");
		config.putNumber("port", 27017);
		config.putNumber("pool_size", 10);
		config.putString("db_name", "lancamp");
		container.deployModule("io.vertx~mod-mongo-persistor~2.1.1-SNAPSHOT", config);

		config = new JsonObject();
		config.putString("address", BC_HANDLE);
		config.putNumber("log_rounds", Integer.valueOf(10));
		container.deployWorkerVerticle(BCryptWorker.class.getCanonicalName(), config, 2);

		config = new JsonObject();
		config.putString("instanceId", instanceId);
		container.deployWorkerVerticle(CompilerVerticle.class.getCanonicalName(), config, 2);
		container.deployVerticle(WebRouter.class.getCanonicalName(), config, Runtime.getRuntime().availableProcessors());
	}
}