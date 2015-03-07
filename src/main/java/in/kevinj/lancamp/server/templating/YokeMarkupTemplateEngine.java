package in.kevinj.lancamp.server.templating;

import groovy.lang.Writable;
import groovy.text.Template;
import groovy.text.markup.MarkupTemplateEngine;
import groovy.text.markup.TemplateConfiguration;

import java.io.IOException;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;

import org.codehaus.groovy.control.CompilationFailedException;
import org.vertx.java.core.AsyncResult;
import org.vertx.java.core.Handler;
import org.vertx.java.core.buffer.Buffer;
import org.vertx.java.core.logging.Logger;

import com.jetdrone.vertx.yoke.core.YokeAsyncResult;
import com.jetdrone.vertx.yoke.engine.AbstractEngineSync;

//TODO: won't work until we fix vertx.gradle. executing Starter.main from
//gradle will force us to use groovy version supplied with gradle (1.8.6) when
//we need to use groovy 2.3 from our classpath. investigate task: JavaExec
public class YokeMarkupTemplateEngine extends AbstractEngineSync<Void> {
	private static final String EXTENSION = ".template";

	private final Logger logger;
	private final String root;

	public YokeMarkupTemplateEngine(Logger logger, String root) {
		this.logger = logger;
		this.root = root;
		//System.out.println(GroovySystem.getVersion());
	}

	@Override
	public String extension() {
		return EXTENSION;
	}

	@Override
	public void render(String file, Map<String, Object> context, Handler<AsyncResult<Buffer>> handler) {
		TemplateConfiguration config = new TemplateConfiguration();
		MarkupTemplateEngine engine = new MarkupTemplateEngine(config);
		Template template;
		try {
			template = engine.createTemplate("p('test template')");
			Map<String, Object> model = new HashMap<>();
			Writable output = template.make(model);
			StringWriter writer = new StringWriter();
			output.writeTo(writer);
			handler.handle(new YokeAsyncResult<>(new Buffer(writer.toString())));
		} catch (CompilationFailedException | ClassNotFoundException | IOException e) {
			logger.error("Template could not be compiled", e);
		}
	}
}
