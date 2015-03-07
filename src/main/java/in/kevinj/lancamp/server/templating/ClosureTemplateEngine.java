package in.kevinj.lancamp.server.templating;

import in.kevinj.lancamp.server.WebRouter;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.vertx.java.core.AsyncResult;
import org.vertx.java.core.Handler;
import org.vertx.java.core.buffer.Buffer;
import org.vertx.java.core.file.FileProps;
import org.vertx.java.core.json.JsonArray;
import org.vertx.java.core.json.JsonElement;
import org.vertx.java.core.json.JsonObject;
import org.vertx.java.core.logging.Logger;

import com.google.template.soy.SoyFileSet;
import com.google.template.soy.base.SoySyntaxException;
import com.google.template.soy.data.SoyData;
import com.google.template.soy.data.SoyListData;
import com.google.template.soy.data.SoyMapData;
import com.google.template.soy.shared.SoyGeneralOptions;
import com.google.template.soy.tofu.SoyTofu;
import com.jetdrone.vertx.yoke.core.YokeAsyncResult;
import com.jetdrone.vertx.yoke.engine.AbstractEngineSync;
import com.jetdrone.vertx.yoke.store.json.ChangeAwareJsonArray;
import com.jetdrone.vertx.yoke.store.json.ChangeAwareJsonObject;

public class ClosureTemplateEngine extends AbstractEngineSync<Void> {
	private static final String EXTENSION = ".soy";

	private static final ReadWriteLock INSTANCES_LOCK = new ReentrantReadWriteLock();
	private static final List<ClosureTemplateEngine> INSTANCES = new ArrayList<>();
	public static ClosureTemplateEngine getInstance(Integer id) {
		INSTANCES_LOCK.readLock().lock();
		try {
			if (id.intValue() >= INSTANCES.size())
				return null;

			return INSTANCES.get(id);
		} finally {
			INSTANCES_LOCK.readLock().unlock();
		}
	}

	private final Integer id;
	private final Logger logger;
	private final String root;
	private List<Handler<Boolean>> onTofuLoaded;
	private volatile SoyFileSet.Builder builder;
	private volatile SoyTofu tofu;

	public ClosureTemplateEngine(Logger logger, String root) {
		this.logger = logger;
		this.root = root;
		onTofuLoaded = null;

		INSTANCES_LOCK.writeLock().lock();
		try {
			if (INSTANCES.add(this))
				id = Integer.valueOf(INSTANCES.size() - 1);
			else
				id = -1;
		} finally {
			INSTANCES_LOCK.writeLock().unlock();
		}
	}

	@Override
	public String extension() {
		return EXTENSION;
	}

	private static SoyData jsonToSoyData(JsonElement json) {
		if (json.isArray()) {
			SoyListData data = new SoyListData();
			JsonArray jsonArr = json.asArray();
			for (Object obj : jsonArr)
				if (obj instanceof Boolean)
					data.add(((Boolean) obj).booleanValue());
				else if (obj instanceof Integer)
					data.add(((Integer) obj).intValue());
				else if (obj instanceof Long)
					data.add(((Long) obj).longValue());
				else if (obj instanceof Number)
					data.add(((Number) obj).doubleValue());
				else if (obj instanceof JsonElement)
					data.add(jsonToSoyData((JsonObject) obj));
				else if (obj instanceof String)
					data.add((String) obj);
			return data;
		} else if (json.isObject()) {
			SoyMapData data = new SoyMapData();
			JsonObject jsonObj = json.asObject();
			for (String field : jsonObj.getFieldNames()) {
				Object obj = jsonObj.getField(field);
				if (obj instanceof Boolean)
					data.put(field, ((Boolean) obj).booleanValue());
				else if (obj instanceof Integer)
					data.put(field, ((Integer) obj).intValue());
				else if (obj instanceof Long)
					data.put(field, ((Long) obj).longValue());
				else if (obj instanceof Number)
					data.put(field, ((Number) obj).doubleValue());
				else if (obj instanceof JsonElement)
					data.put(field, jsonToSoyData((JsonObject) obj));
				else if (obj instanceof String)
					data.put(field, (String) obj);
			}
			return data;
		} else {
			return null;
		}
	}

	private void renderInternal(String file, Map<String, Object> context, Handler<AsyncResult<Buffer>> handler, Boolean success) {
		Map<String, Object> subs = new HashMap<>();
		for (Map.Entry<String, Object> obj : context.entrySet()) {
			if (obj.getValue() instanceof ChangeAwareJsonObject)
				subs.put(obj.getKey(), jsonToSoyData(((ChangeAwareJsonObject) obj.getValue()).jsonObject()));
			else if (obj.getValue() instanceof ChangeAwareJsonArray)
				subs.put(obj.getKey(), jsonToSoyData(((ChangeAwareJsonArray) obj.getValue()).unsafeJsonArray()));
			else if (obj.getValue() instanceof JsonElement)
				subs.put(obj.getKey(), jsonToSoyData((JsonElement) obj.getValue()));
			else
				subs.put(obj.getKey(), obj.getValue());
		}
		subs.put("routes", WebRouter.Routes.forSoy);
		if (success.booleanValue())
			handler.handle(new YokeAsyncResult<>(new Buffer(tofu.newRenderer(file.substring(0, file.length() - EXTENSION.length())).setData(subs).setCssRenamingMap(GoogleClosureState.INSTANCE.htmlCssMapper).render())));
		else
			handler.handle(new YokeAsyncResult<>(new Buffer()));
	}

	@Override
	public void render(String file, Map<String, Object> context, Handler<AsyncResult<Buffer>> handler) {
		if (tofu != null) //already compiled
			renderInternal(file, context, handler, Boolean.valueOf(tofu != null));
		else if (onTofuLoaded != null) //queue up on the compile operation in progress
			onTofuLoaded.add(success -> renderInternal(file, context, handler, success));
		else //start a new compile operation
			compileTemplates(success -> renderInternal(file, context, handler, success));
	}

	protected String build() {
		if (builder == null)
			return "Invalid state";

		try {
			tofu = builder.build().compileToTofu();
			return "";
		} catch (SoySyntaxException ex) {
			return ex.getMessage() + " (" + ex.getClass().getCanonicalName() + ")";
		}
	}

	private void builderReady() {
		//TODO: classloader isolation makes it impossible to access this instance
		//from CompilerVerticle. apparently vertx 3.0 will be more flexible though...
		//vertx.eventBus().send(CompilerVerticle.class.getCanonicalName() + ".soy", id, (Message<String> message) -> {
			//String result = message.body();
			String result = getInstance(id).build();
			Boolean success = Boolean.valueOf(result.isEmpty());
			if (!result.isEmpty())
				logger.error("HTML not compiled: " + result);
			for (Handler<Boolean> next : onTofuLoaded)
				next.handle(success);
			onTofuLoaded = null;
			builder = null;
		//});
	}

	private void enterDirectory(String folder, AsyncResult<String[]> event, Set<String> waitingOnFiles) {
		if (!event.succeeded()) {
			logger.error("Could not read " + folder);
			return;
		}

		for (String file : event.result()) {
			String canonical = folder + '/' + file.substring(Math.max(file.lastIndexOf(File.separatorChar), file.lastIndexOf('/')) + 1);
			waitingOnFiles.add(canonical);
			vertx.fileSystem().props(canonical, event2 -> {
				if (!event2.succeeded()) {
					logger.error("Could not read " + canonical);
					return;
				}

				FileProps result = event2.result();
				if (result.isDirectory()) {
					vertx.fileSystem().readDir(canonical, event3 -> enterDirectory(canonical, event3, waitingOnFiles));
				} else if (result.isRegularFile()) {
					if (canonical.endsWith(EXTENSION))
						builder.add(new File(file));

					waitingOnFiles.remove(canonical);
					if (waitingOnFiles.isEmpty())
						builderReady();
				}
			});
		}
		waitingOnFiles.remove(folder);
		if (waitingOnFiles.isEmpty())
			builderReady();
	}

	private void compileTemplates(Handler<Boolean> next) {
		onTofuLoaded = new ArrayList<>();
		onTofuLoaded.add(next);
		vertx.fileSystem().exists(root, event1 -> {
			if (!event1.result().booleanValue()) {
				logger.error(root + " does not exist");
				return;
			}

			builder = SoyFileSet.builder();
			Map<String, String> globals = new HashMap<>();
			globals.put("ROOT_DIR", WebRouter.Routes.WEB_ROOT);
			globals.put("ASSETS", "/assets");
			builder.setCompileTimeGlobals(globals);
			builder.setCssHandlingScheme(SoyGeneralOptions.CssHandlingScheme.BACKEND_SPECIFIC);
			Set<String> processing = new HashSet<>();
			processing.add(root);
			vertx.fileSystem().readDir(root, event3 -> enterDirectory(root, event3, processing));
		});
	}
}
