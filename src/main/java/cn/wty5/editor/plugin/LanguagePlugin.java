package cn.wty5.editor.plugin;

import cn.wty5.editor.lang.LanguageSpec;
import cn.wty5.editor.lang.Lexer;
import cn.wty5.editor.lsp.LspConfig;

/**
 * A language plugin contributes a {@link LanguageSpec} and optionally a
 * custom {@link Lexer} / {@link LspConfig}. When {@link #createLexer} returns
 * null the editor falls back to a {@link cn.wty5.editor.lang.GrammarLexer}
 * driven by the spec.
 *
 * Plugins are discovered either by:
 * <ul>
 *   <li>loading a grammar JSON via {@link cn.wty5.editor.lang.LanguageRegistry}, or</li>
 *   <li>installing a jar that declares a {@code LanguagePlugin} implementation
 *       (see {@link PluginManager}).</li>
 * </ul>
 *
 * <p>LSP configuration resolution order when the editor attaches a server:
 * <ol>
 *   <li>{@link #getLspConfig()} if non-null (plugin override)</li>
 *   <li>{@link LanguageSpec#lsp} from the grammar / {@link #getSpec()}</li>
 * </ol>
 */
public interface LanguagePlugin {

    /** Human-readable language name, e.g. "java", "go". */
    String getName();

    /** File extensions this plugin claims (without the leading dot). */
    String[] getExtensions();

    /** Lexical / completion surface for the language. */
    LanguageSpec getSpec();

    /**
     * Optional custom lexer. Return null to use the generic grammar lexer
     * built from {@link #getSpec()}.
     */
    default Lexer createLexer() {
        return null;
    }

    /**
     * Optional language-server connection. Return null to fall back to
     * {@link LanguageSpec#lsp} from {@link #getSpec()}, or to disable LSP
     * entirely when the spec also has none.
     *
     * <p>Typical use: a plugin jar that ships its own language-server binary
     * and wants to point {@code command} at an extracted path that is only
     * known at runtime.
     */
    default LspConfig getLspConfig() {
        return null;
    }
}
