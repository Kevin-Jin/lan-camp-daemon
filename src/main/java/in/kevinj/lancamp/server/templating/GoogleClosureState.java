package in.kevinj.lancamp.server.templating;

import com.google.common.base.Function;
import com.google.javascript.jscomp.CssRenamingMap;
import com.google.template.soy.shared.SoyCssRenamingMap;

//TODO: won't work until vertx 3.0 is released and there is more flexibility
//with classloader isolation
//TODO: read htmlCssMapper/jsCssMapper from closure-stylesheets output-renaming-map,
//launch closure-stylesheets process with Runtime.getRuntime().exec()
public final class GoogleClosureState {
	private final Function<String, String> cssMapper;
	public final SoyCssRenamingMap htmlCssMapper;
	public final CssRenamingMap jsCssMapper;

	@SuppressWarnings("serial")
	private GoogleClosureState() {
		//identity map for now until we implement CSS minimization renaming
		cssMapper = key -> key;
		htmlCssMapper = cssMapper::apply;
		jsCssMapper = new CssRenamingMap.ByWhole() {
			@Override
			public String get(String value) {
				return cssMapper.apply(value);
			}
		};
	}

	public static final GoogleClosureState INSTANCE = new GoogleClosureState();
}
