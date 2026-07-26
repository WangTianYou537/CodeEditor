package cn.wty5.editor.demo;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.InputStream;

import cn.wty5.editor.lang.GrammarLoader;
import cn.wty5.editor.lang.LanguageRegistry;
import cn.wty5.editor.lang.LanguageSpec;
import cn.wty5.editor.view.CodeEditorView;

/**
 * Minimal host activity that embeds {@link CodeEditorView} full-screen
 * under a thin toolbar (language switch, undo/redo, zoom).
 */
public class MainActivity extends Activity {

    private CodeEditorView editor;

    private static final String SAMPLE_JAVA =
            "package demo;\n"
          + "\n"
          + "import java.util.ArrayList;\n"
          + "import java.util.List;\n"
          + "\n"
          + "/**\n"
          + " * Sample Java file — try editing, undoing, pinching to zoom,\n"
          + " * and typing identifiers to trigger completions.\n"
          + " */\n"
          + "public class Hello {\n"
          + "    private int counter = 0;\n"
          + "\n"
          + "    public static void main(String[] args) {\n"
          + "        Hello h = new Hello();\n"
          + "        h.increment();\n"
          + "        System.out.println(\"count = \" + h.counter);\n"
          + "    }\n"
          + "\n"
          + "    public void increment() {\n"
          + "        // hold backspace in this comment — colour should not flash\n"
          + "        counter++;\n"
          + "    }\n"
          + "\n"
          + "    public int getThreadBinding() {\n"
          + "        return counter;\n"
          + "    }\n"
          + "}\n";

    private static final String SAMPLE_GO =
            "package main\n"
          + "\n"
          + "import \"fmt\"\n"
          + "\n"
          + "// Sample Go file — switch language with the toolbar buttons.\n"
          + "func main() {\n"
          + "\tdefer fmt.Println(\"done\")\n"
          + "\ttotal := computeTotal(3, 4)\n"
          + "\tfmt.Printf(\"total = %d\\n\", total)\n"
          + "}\n"
          + "\n"
          + "func computeTotal(a, b int) int {\n"
          + "\tif err := validate(a); err != nil {\n"
          + "\t\treturn 0\n"
          + "\t}\n"
          + "\treturn a + b\n"
          + "}\n"
          + "\n"
          + "func validate(n int) error {\n"
          + "\tif n < 0 {\n"
          + "\t\treturn fmt.Errorf(\"negative: %d\", n)\n"
          + "\t}\n"
          + "\treturn nil\n"
          + "}\n"
          + "\n"
          + "const banner = `raw\n"
          + "multi-line\n"
          + "string`\n";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        FrameLayout container = findViewById(R.id.editor_container);
        editor = new CodeEditorView(this);
        container.addView(editor, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // Prefer the packaged grammar JSON (assets) over hard-coded fallbacks.
        loadGrammarsFromAssets();

        editor.setLanguage("java");
        editor.setText(SAMPLE_JAVA);

        wire(R.id.btn_java, v -> {
            editor.setLanguage("java");
            editor.setText(SAMPLE_JAVA);
            toast("Java");
        });
        wire(R.id.btn_go, v -> {
            editor.setLanguage("go");
            editor.setText(SAMPLE_GO);
            toast("Go");
        });
        wire(R.id.btn_undo, v -> {
            if (editor.canUndo()) editor.undo();
            else toast("Nothing to undo");
        });
        wire(R.id.btn_redo, v -> {
            if (editor.canRedo()) editor.redo();
            else toast("Nothing to redo");
        });
        wire(R.id.btn_zoom_in, v ->
                editor.setTextSizeSp(editor.getTextSizeSp() + 1f));
        wire(R.id.btn_zoom_out, v ->
                editor.setTextSizeSp(editor.getTextSizeSp() - 1f));
    }


    /** Loads /assets/grammars/*.json into the shared registry (overrides builtins). */
    private void loadGrammarsFromAssets() {
        LanguageRegistry reg = LanguageRegistry.getInstance();
        String[] names = {"java", "go"};
        for (String name : names) {
            try (InputStream in = getAssets().open("grammars/" + name + ".json")) {
                LanguageSpec spec = GrammarLoader.load(in);
                reg.register(spec);
            } catch (Exception e) {
                // Fallbacks already registered by CodeEditorView; ignore.
            }
        }
        // Refresh the editor's language object if it was the builtin instance.
        if (editor != null && editor.getLanguage() != null) {
            String cur = editor.getLanguage().name;
            LanguageSpec fresh = reg.getSpec(cur);
            if (fresh != null) editor.setLanguage(fresh);
        }
    }

    private void wire(int id, View.OnClickListener l) {
        Button b = findViewById(id);
        if (b != null) b.setOnClickListener(l);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
