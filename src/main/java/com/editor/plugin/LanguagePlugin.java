package com.editor.plugin;

import com.editor.lang.LanguageSpec;
import com.editor.lang.Lexer;

/**
 * A language plugin contributes a {@link LanguageSpec} and optionally a
 * custom {@link Lexer}. When {@link #createLexer} returns null the editor
 * falls back to a {@link com.editor.lang.GrammarLexer} driven by the spec.
 *
 * Plugins are discovered either by:
 * <ul>
 *   <li>loading a grammar JSON via {@link com.editor.lang.LanguageRegistry}, or</li>
 *   <li>installing a jar that declares a {@code LanguagePlugin} implementation
 *       (see {@link PluginManager}).</li>
 * </ul>
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
}
