package cn.wty5.editor.lsp;

/**
 * One diagnostic published by a language server
 * ({@code textDocument/publishDiagnostics}).
 *
 * Line/character are 0-based, UTF-16 code units — identical to the editor's
 * document offsets after {@code Document.offsetAt(line, character)}.
 */
public final class Diagnostic {

    public static final int SEVERITY_ERROR = 1;
    public static final int SEVERITY_WARNING = 2;
    public static final int SEVERITY_INFORMATION = 3;
    public static final int SEVERITY_HINT = 4;

    public final int startLine;
    public final int startCharacter;
    public final int endLine;
    public final int endCharacter;
    /** LSP DiagnosticSeverity; defaults to ERROR when the server omits it. */
    public final int severity;
    public final String message;
    public final String source;
    public final String code;

    public Diagnostic(int startLine, int startCharacter,
                      int endLine, int endCharacter,
                      int severity, String message,
                      String source, String code) {
        this.startLine = Math.max(0, startLine);
        this.startCharacter = Math.max(0, startCharacter);
        this.endLine = Math.max(this.startLine, endLine);
        this.endCharacter = Math.max(0, endCharacter);
        this.severity = severity <= 0 ? SEVERITY_ERROR : severity;
        this.message = message == null ? "" : message;
        this.source = source == null ? "" : source;
        this.code = code == null ? "" : code;
    }

    public boolean isError() {
        return severity == SEVERITY_ERROR;
    }

    public boolean isWarning() {
        return severity == SEVERITY_WARNING;
    }
}
