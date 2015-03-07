package in.kevinj.lancamp.server.templating;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.lesscss.LessCompiler;
import org.lesscss.LessException;
import org.vertx.java.core.buffer.Buffer;
import org.vertx.java.core.eventbus.Message;
import org.vertx.java.core.file.FileSystem;
import org.vertx.java.core.json.JsonObject;
import org.vertx.java.core.logging.Logger;
import org.vertx.java.platform.Verticle;

import com.google.javascript.jscomp.CommandLineRunner;
import com.google.javascript.jscomp.CompilationLevel;
import com.google.javascript.jscomp.Compiler;
import com.google.javascript.jscomp.CompilerOptions;
import com.google.javascript.jscomp.Result;
import com.google.javascript.jscomp.SourceFile;

public class CompilerVerticle extends Verticle {
	private void jsMinify(Message<JsonObject> event) {
		Logger logger = container.logger();
		FileSystem fileSystem = vertx.fileSystem();
		JsonObject json = event.body();
		String source = json.getString("source");
		String dest = json.getString("dest");

		Compiler compiler = new Compiler();
		CompilerOptions options = new CompilerOptions();
		options.setCssRenamingMap(GoogleClosureState.INSTANCE.jsCssMapper);
		CompilationLevel.SIMPLE_OPTIMIZATIONS.setOptionsForCompilationLevel(options);
		List<SourceFile> externs;
		try {
			externs = CommandLineRunner.getDefaultExterns();
		} catch (IOException e) {
			logger.warn("Failed to load default externs", e);
			externs = new ArrayList<>();
		}
		File loadFile;
		try {
			//TODO: detect if preserve-cwd
			URI uri = Thread.currentThread().getContextClassLoader().getResource(source).toURI();
			loadFile = new File(uri);
			if (!loadFile.exists())
				throw new RuntimeException("\"" + source + "\" exists but \"" + uri.toString() + "\" does not");
		} catch (URISyntaxException | RuntimeException e) {
			logger.warn("Failed to resolve source JavaScript file", e);
			vertx.sharedData().getMap(event.address() + "[" + container.config().getString("instanceId") + "]").remove(source);
			vertx.eventBus().publish(event.address() + ".done[" + dest + "]", Boolean.FALSE);
			event.reply(Boolean.FALSE);
			return;
		}
		Result compileResult = compiler.compile(externs, Collections.singletonList(SourceFile.fromFile(loadFile)), options);
		if (compileResult.success) {
			fileSystem.writeFile(dest, new Buffer(compiler.toSource()), writeResult -> {
				if (writeResult.succeeded()) {
					vertx.sharedData().getMap(event.address() + "[" + container.config().getString("instanceId") + "]").remove(source);
					vertx.eventBus().publish(event.address() + ".done[" + dest + "]", Boolean.TRUE);
					event.reply(Boolean.TRUE);
				} else {
					logger.warn("Failed to save minified JavaScript file", writeResult.cause());
					vertx.sharedData().getMap(event.address() + "[" + container.config().getString("instanceId") + "]").remove(source);
					vertx.eventBus().publish(event.address() + ".done[" + dest + "]", Boolean.FALSE);
					event.reply(Boolean.FALSE);
				}
			});
		} else {
			logger.warn("Failed to compile JavaScript file at \"" + source + "\":\n" + String.join("\n", Arrays.stream(compileResult.errors).map(e -> e.toString()).collect(Collectors.toList())));
			vertx.sharedData().getMap(event.address() + "[" + container.config().getString("instanceId") + "]").remove(source);
			vertx.eventBus().publish(event.address() + ".done[" + dest + "]", Boolean.FALSE);
			event.reply(Boolean.FALSE);
		}
	}

	private void lessToCss(Message<JsonObject> event) {
		Logger logger = container.logger();
		FileSystem fileSystem = vertx.fileSystem();
		JsonObject json = event.body();
		String source = json.getString("source");
		String dest = json.getString("dest");

		LessCompiler compiler = new LessCompiler();
		File loadFile;
		try {
			//TODO: detect if preserve-cwd
			URI uri = Thread.currentThread().getContextClassLoader().getResource(source).toURI();
			loadFile = new File(uri);
			if (!loadFile.exists())
				throw new RuntimeException("\"" + source + "\" exists but \"" + uri.toString() + "\" does not");
		} catch (URISyntaxException | RuntimeException e) {
			logger.warn("Failed to resolve source LESS file", e);
			vertx.sharedData().getMap(event.address() + "[" + container.config().getString("instanceId") + "]").remove(source);
			vertx.eventBus().publish(event.address() + ".done[" + dest + "]", Boolean.FALSE);
			event.reply(Boolean.FALSE);
			return;
		}
		try {
			fileSystem.writeFile(dest, new Buffer(compiler.compile(loadFile)), writeResult -> {
				if (writeResult.succeeded()) {
					vertx.sharedData().getMap(event.address() + "[" + container.config().getString("instanceId") + "]").remove(source);
					vertx.eventBus().publish(event.address() + ".done[" + dest + "]", Boolean.TRUE);
					event.reply(Boolean.TRUE);
				} else {
					logger.warn("Failed to save generated CSS file", writeResult.cause());
					vertx.sharedData().getMap(event.address() + "[" + container.config().getString("instanceId") + "]").remove(source);
					vertx.eventBus().publish(event.address() + ".done[" + dest + "]", Boolean.FALSE);
					event.reply(Boolean.FALSE);
				}
			});
		} catch (IOException | LessException e) {
			logger.warn("Failed to compile LESS file", e);
			vertx.sharedData().getMap(event.address() + "[" + container.config().getString("instanceId") + "]").remove(source);
			vertx.eventBus().publish(event.address() + ".done[" + dest + "]", Boolean.FALSE);
			event.reply(Boolean.FALSE);
		}
	}

	private void cssMinify(Message<JsonObject> event) {
		Logger logger = container.logger();
		FileSystem fileSystem = vertx.fileSystem();
		JsonObject json = event.body();
		String source = json.getString("source");
		String dest = json.getString("dest");

		//TODO: implement
		fileSystem.copy(source, dest, copyResult -> {
			if (copyResult.succeeded()) {
				vertx.sharedData().getMap(event.address() + "[" + container.config().getString("instanceId") + "]").remove(source);
				vertx.eventBus().publish(event.address() + ".done[" + dest + "]", Boolean.TRUE);
				event.reply(Boolean.TRUE);
			} else {
				logger.warn("Failed to save minified CSS file", copyResult.cause());
				vertx.sharedData().getMap(event.address() + "[" + container.config().getString("instanceId") + "]").remove(source);
				vertx.eventBus().publish(event.address() + ".done[" + dest + "]", Boolean.FALSE);
				event.reply(Boolean.FALSE);
			}
		});
	}

	private void soyBuild(Message<Integer> event) {
		ClosureTemplateEngine engine = ClosureTemplateEngine.getInstance(event.body());
		if (engine == null)
			event.reply("Invalid ClosureTemplateEngine ID passed");
		else
			event.reply(engine.build());
	}

	@Override
	public void start() {
		vertx.eventBus().registerLocalHandler(CompilerVerticle.class.getCanonicalName() + ".js", this::jsMinify);
		vertx.eventBus().registerLocalHandler(CompilerVerticle.class.getCanonicalName() + ".less", this::lessToCss);
		vertx.eventBus().registerLocalHandler(CompilerVerticle.class.getCanonicalName() + ".css", this::cssMinify);
		vertx.eventBus().registerLocalHandler(CompilerVerticle.class.getCanonicalName() + ".soy", this::soyBuild);
	}
}
