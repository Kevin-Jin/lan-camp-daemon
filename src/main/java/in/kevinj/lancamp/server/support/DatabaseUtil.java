package in.kevinj.lancamp.server.support;

import in.kevinj.lancamp.server.Boot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.vertx.java.core.AsyncResult;
import org.vertx.java.core.Handler;
import org.vertx.java.core.Vertx;
import org.vertx.java.core.eventbus.Message;
import org.vertx.java.core.json.JsonArray;
import org.vertx.java.core.json.JsonObject;

public class DatabaseUtil {
	public static class QueryResult implements Iterable<JsonArray> {
		private final JsonObject base;
		private Iterator<?> iterator;
		private JsonObject cursor;

		public QueryResult(JsonObject base) {
			this.base = base;
			if (base.containsField("results"))
				this.iterator = base.getArray("results").iterator();
			else if (base.containsField("result"))
				this.iterator = Collections.singleton(base.getObject("result")).iterator();
			else
				this.iterator = Collections.emptyIterator();
		}

		public int affectedRows() {
			return base.getNumber("rows").intValue();
		}

		public <T> T getValue(String key) {
			return cursor.getValue(key);
		}

		public int getInt(String key) {
			return this.<Number>getValue(key).intValue();
		}

		public long getLong(String key) {
			return this.<Number>getValue(key).longValue();
		}

		public double getDouble(String key) {
			return this.<Number>getValue(key).doubleValue();
		}

		public float getFloat(String key) {
			return this.<Number>getValue(key).floatValue();
		}

		public boolean getBoolean(String key) {
			return this.<Boolean>getValue(key).booleanValue();
		}

		public boolean hasNext() {
			return iterator.hasNext();
		}

		public QueryResult next() {
			if (iterator.hasNext())
				cursor = (JsonObject) iterator.next();
			else
				cursor = null;
			return this;
		}

		@Override
		public Iterator<JsonArray> iterator() {
			JsonArray results = base.getArray("results");
			List<JsonArray> rows = new ArrayList<>(results.size());
			results.forEach(obj -> rows.add((JsonArray) obj));
			return rows.iterator();
		}
	}

	private static void onSimpleResult(AsyncResult<Message<JsonObject>> dbResp, Handler<Throwable> failure, Handler<Pair<Message<JsonObject>, QueryResult>> success) {
		if (dbResp.failed()) {
			failure.handle(new Throwable("operation timed out", dbResp.cause()));
			return;
		}
		switch (dbResp.result().body().getString("status")) {
			default:
				if (dbResp.result().body().containsField("exception"))
					failure.handle(new Throwable(dbResp.result().body().getString("message") + "\n" + dbResp.result().body().getString("exception")));
				else
					failure.handle(new Throwable(dbResp.result().body().getString("message")));
				break;
			case "ok":
				success.handle(new Pair<>(dbResp.result(), new QueryResult(dbResp.result().body())));
				break;
		}
	}

	public static void insert(Vertx vertx, String collection, JsonObject value, Handler<Throwable> failure, Handler<Pair<Message<JsonObject>, QueryResult>> success) {
		vertx.eventBus().<JsonObject>sendWithTimeout(Boot.DB_HANDLE,
			(new JsonObject()
				.putString("action", "save")
				.putString("collection", collection)
				.putObject("document", value)
			),
			UserAuth.EBUS_TIMEOUT,
			dbResp -> onSimpleResult(dbResp, failure, success)
		);
	}

	public static void find(Vertx vertx, String collection, JsonObject query, JsonObject fields, Handler<Throwable> failure, Handler<Pair<Message<JsonObject>, QueryResult>> success) {
		vertx.eventBus().<JsonObject>sendWithTimeout(Boot.DB_HANDLE,
			(new JsonObject()
				.putString("action", "find")
				.putString("collection", collection)
				.putObject("matcher", query)
				.putObject("keys", fields)
			),
			UserAuth.EBUS_TIMEOUT,
			dbResp -> onSimpleResult(dbResp, failure, success)
		);
	}

	public static void update(Vertx vertx, String collection, JsonObject query, JsonObject objNew, Handler<Throwable> failure, Handler<Pair<Message<JsonObject>, QueryResult>> success) {
		vertx.eventBus().<JsonObject>sendWithTimeout(Boot.DB_HANDLE,
			(new JsonObject()
				.putString("action", "update")
				.putString("collection", collection)
				.putObject("criteria", query)
				.putObject("objNew", objNew)
			),
			UserAuth.EBUS_TIMEOUT,
			dbResp -> onSimpleResult(dbResp, failure, success)
		);
	}

	public static void remove(Vertx vertx, String collection, JsonObject query, Handler<Throwable> failure, Handler<Pair<Message<JsonObject>, QueryResult>> success) {
		vertx.eventBus().<JsonObject>sendWithTimeout(Boot.DB_HANDLE,
			(new JsonObject()
				.putString("action", "delete")
				.putString("collection", collection)
				.putObject("matcher", query)
			),
			UserAuth.EBUS_TIMEOUT,
			dbResp -> onSimpleResult(dbResp, failure, success)
		);
	}

	public static void findAndModify(Vertx vertx, String collection, JsonObject query, JsonObject objNew, JsonObject fields, boolean upsert, Handler<Throwable> failure, Handler<Pair<Message<JsonObject>, QueryResult>> success) {
		vertx.eventBus().<JsonObject>sendWithTimeout(Boot.DB_HANDLE,
			(new JsonObject()
				.putString("action", "find_and_modify")
				.putString("collection", collection)
				.putObject("matcher", query)
				.putObject("fields", fields)
				.putObject("update", objNew)
				.putBoolean("upsert", upsert)
			),
			UserAuth.EBUS_TIMEOUT,
			dbResp -> onSimpleResult(dbResp, failure, success)
		);
	}

	public static void ensureIndex(Vertx vertx, String collection, String name, boolean unique, JsonObject fields, Handler<Throwable> failure, Handler<Pair<Message<JsonObject>, QueryResult>> success) {
		vertx.eventBus().<JsonObject>sendWithTimeout(Boot.DB_HANDLE,
			(new JsonObject()
				.putString("action", "command")
				.putString("command", /*new JsonObject()
					.putString("createIndexes", collection)
					.putArray("indexes", new JsonArray()
						.addObject(new JsonObject()
							.putObject("key", fields)
							.putString("name", name)
							.putBoolean("unique", unique)
						)
					).toString()*/ new JsonObject()
					.putString("eval", "function() { return db." + collection + ".ensureIndex(" + fields.encode() + ",{ \"unique\": " + unique + " }) }")
					.toString()
				)
			),
			UserAuth.EBUS_TIMEOUT,
			dbResp -> onSimpleResult(dbResp, failure, success)
		);
	}
}
