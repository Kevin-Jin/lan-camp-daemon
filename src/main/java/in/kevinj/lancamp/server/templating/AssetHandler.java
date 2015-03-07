package in.kevinj.lancamp.server.templating;

import java.io.File;

import org.vertx.java.core.AsyncResult;
import org.vertx.java.core.Handler;
import org.vertx.java.core.Vertx;
import org.vertx.java.core.eventbus.Message;
import org.vertx.java.core.file.FileSystem;
import org.vertx.java.core.json.JsonObject;
import org.vertx.java.platform.Container;

import com.jetdrone.vertx.yoke.Middleware;
import com.jetdrone.vertx.yoke.Yoke;
import com.jetdrone.vertx.yoke.middleware.Static;
import com.jetdrone.vertx.yoke.middleware.YokeRequest;

public class AssetHandler extends Middleware {
	private static final int COMPILE_TIMEOUT = 3000;

	private final Vertx vertx;
	private final Container container;
	private final String instanceId;
	private final String srcRoot, dstRoot;
	private final Static regularAssets, compiledAssets;

	public AssetHandler(Vertx vertx, Container container, String instanceId, String srcRoot, String dstRoot) {
		this.vertx = vertx;
		this.container = container;
		this.instanceId = instanceId;
		this.srcRoot = srcRoot;
		this.dstRoot = dstRoot;
		this.regularAssets = new Static(srcRoot);
		this.compiledAssets = new Static(dstRoot);
	}

	@Override
	public Middleware init(final Yoke yoke, final String mount) {
		regularAssets.init(yoke, mount);
		compiledAssets.init(yoke, mount);
		return super.init(yoke, mount);
	}

	private void compile(YokeRequest req, String source, boolean precompiled, String oldExtension, String newExtension, Handler<String> success, Handler<String> fail, boolean[] isRespClosed) {
		FileSystem fileSystem = vertx.fileSystem();
		String dest = dstRoot + source.substring((precompiled ? dstRoot : srcRoot).length(), source.length() - oldExtension.length()) + newExtension;
		String address = CompilerVerticle.class.getCanonicalName() + oldExtension;

		fileSystem.mkdirSync(dest.substring(0, Math.max(dest.lastIndexOf('/'), dest.lastIndexOf(File.separatorChar))), true);
		if (fileSystem.existsSync(dest) && (!fileSystem.existsSync(source) || fileSystem.propsSync(dest).lastModifiedTime().getTime() >= fileSystem.propsSync(source).lastModifiedTime().getTime())) {
			//already compiled at its latest version
			if (isRespClosed[0])
				return;

			success.handle(dest);
		} else if (vertx.sharedData().getMap(address + "[" + instanceId + "]").putIfAbsent(source, Boolean.TRUE) != null) {
			//compile in progress
			long[] timer = new long[1];
			Handler<Message<Boolean>> finishedHandler = result -> {
				vertx.cancelTimer(timer[0]);
				if (isRespClosed[0])
					return;

				if (result.body().booleanValue())
					success.handle(dest);
				else
					fail.handle("Compile failed");
			};
			timer[0] = vertx.setTimer(COMPILE_TIMEOUT, id -> {
				vertx.eventBus().unregisterHandler(address + ".done[" + dest + "]", finishedHandler);
				if (isRespClosed[0])
					return;

				if (!vertx.sharedData().getMap(address + "[" + instanceId + "]").containsKey(source)) {
					//most likely a race condition where compiler wasn't done yet
					//when we retrieved alreadyLoading, but finished before we
					//registered finishedHandler
					success.handle(dest);
				} else {
					container.logger().warn("Did not receive a compiled copy of \"" + source + "\" in a timely manner");
					fail.handle("Compile timed out");
				}
			});
			vertx.eventBus().registerLocalHandler(address + ".done[" + dest + "]", finishedHandler);
		} else if (!fileSystem.existsSync(dest) || fileSystem.propsSync(dest).lastModifiedTime().getTime() < fileSystem.propsSync(source).lastModifiedTime().getTime()) {
			//compile not initiated
			JsonObject json = new JsonObject();
			json.putString("source", source);
			json.putString("dest", dest);
			vertx.eventBus().sendWithTimeout(address, json, COMPILE_TIMEOUT, (AsyncResult<Message<Boolean>> event) -> {
				if (isRespClosed[0])
					return;

				if (event.succeeded()) {
					if (event.result().body().booleanValue())
						success.handle(dest);
					else
						fail.handle("Compile failed");
				} else {
					container.logger().warn("Did not receive a compiled copy of \"" + source + "\" in a timely manner");
					fail.handle("Compile timed out");
				}
			});
		}
	}

	private void baseFileReady(YokeRequest req, String srcPath, boolean precompiled, boolean[] isRespClosed, Handler<Object> next) {
		boolean js = srcPath.endsWith(".js");
		boolean css = srcPath.endsWith(".css");
		boolean minifiedPreferable = req.query() != null && req.query().contains("min");
		if (js && minifiedPreferable)
			//client wants the source file in minified form. make sure minified file is up to date
			compile(req, srcPath, precompiled, ".js", ".js", dest -> compiledAssets.handle(req, next), reason -> regularAssets.handle(req, next), isRespClosed);
		else if (css && minifiedPreferable)
			//client wants the source file in minified form. make sure minified file is up to date
			compile(req, srcPath, precompiled, ".css", ".css", dest -> compiledAssets.handle(req, next), reason -> regularAssets.handle(req, next), isRespClosed);
		else if (precompiled)
			//e.g. .less file
			compiledAssets.handle(req, next);
		else
			//no minified files requested or none available
			regularAssets.handle(req, next);
	}

	private void handleInternal(boolean[] isRespClosed, YokeRequest req, Handler<Object> next) {
		String reqPath = req.normalizedPath().substring(mount.length());
		String srcPath = srcRoot + reqPath;
		if (srcPath.endsWith(".css") && vertx.fileSystem().existsSync(srcPath = srcPath.substring(0, srcPath.length() - ".css".length()) + ".less"))
			//even if the .css file exists, call compile to ensure it's modified after .less file was 
			compile(req, srcPath, false, ".less", ".css", dest -> baseFileReady(req, dstRoot + reqPath, true, isRespClosed, next), reason -> req.response().setStatusCode(500).end("Failed to compile LESS to CSS"), isRespClosed);
		else if (vertx.fileSystem().existsSync(srcPath))
			//file exists
			baseFileReady(req, srcPath, false, isRespClosed, next);
		else
			//the file does not exist
			regularAssets.handle(req, next);
		
	}

	@Override
	public void handle(YokeRequest req, Handler<Object> next) {
		boolean[] isRespClosed = { false };
		try {
			req.response().closeHandler(v -> isRespClosed[0] = true);
			handleInternal(isRespClosed, req, next);
		} catch (Throwable t) {
			if (!isRespClosed[0])
				next.handle(t.toString());
		}
	}
}
