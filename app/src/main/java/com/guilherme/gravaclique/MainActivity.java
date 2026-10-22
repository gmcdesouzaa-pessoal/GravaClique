package com.guilherme.gravaclique;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private TextView txtServico;
    private EditText edReps, edPausa;

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private TextView texto(String s, int tamanho, boolean negrito) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(tamanho);
        if (negrito) t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setPadding(0, dp(8), 0, dp(4));
        return t;
    }

    private Button botao(String s, Runnable acao) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setOnClickListener(v -> acao.run());
        return b;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(16), dp(20), dp(24));
        scroll.addView(col);

        col.addView(texto("GravaClique", 24, true));
        col.addView(texto("Grave seus toques na tela e repita quantas vezes quiser.", 15, false));

        txtServico = texto("", 15, true);
        col.addView(txtServico);

        col.addView(texto("Passo 1: ativar o serviço", 17, true));
        col.addView(texto("Abra Acessibilidade, procure \"GravaClique\" (pode estar em "
                + "\"Apps instalados\" ou \"Serviços baixados\") e ative.", 14, false));
        col.addView(botao("Abrir configurações de Acessibilidade", () ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));

        col.addView(texto("Se a chave estiver bloqueada (Android 13 ou mais novo):", 14, true));
        col.addView(texto("Abra as informações do app, toque nos 3 pontinhos no canto superior "
                + "direito e escolha \"Permitir configurações restritas\". Depois volte ao Passo 1.", 14, false));
        col.addView(botao("Abrir informações do app", () -> {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        }));

        col.addView(texto("Passo 2: configurar", 17, true));
        col.addView(texto("Quantas vezes repetir (0 = sem parar):", 14, false));
        edReps = new EditText(this);
        edReps.setInputType(InputType.TYPE_CLASS_NUMBER);
        col.addView(edReps);
        col.addView(texto("Pausa entre uma repetição e outra (milissegundos):", 14, false));
        edPausa = new EditText(this);
        edPausa.setInputType(InputType.TYPE_CLASS_NUMBER);
        col.addView(edPausa);
        col.addView(botao("Salvar configuração", this::salvarConfig));

        col.addView(texto("Passo 3: usar", 17, true));
        col.addView(botao("Mostrar painel flutuante", () -> {
            if (ClickService.instance != null) {
                ClickService.instance.mostrarPainel();
                moveTaskToBack(true);
            } else {
                Toast.makeText(this, "Ative o serviço primeiro (Passo 1)", Toast.LENGTH_LONG).show();
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        }));
        col.addView(texto(
                "● REC: começa a gravar. A tela fica levemente avermelhada. Toque e arraste "
                + "normalmente, cada toque é gravado e executado no app.\n"
                + "■: para a gravação (ou a reprodução).\n"
                + "- e +: escolhem quantas vezes repetir. Segure para pular de 10 em 10. "
                + "Abaixo de 1 vira ∞ (sem parar).\n"
                + "▶: reproduz a gravação.\n"
                + "✥ mover: arraste o painel para longe dos pontos gravados.\n"
                + "✕: esconde o painel (volte aqui para mostrar de novo).", 14, false));

        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        SharedPreferences p = getSharedPreferences(ClickService.PREFS, MODE_PRIVATE);
        edReps.setText(String.valueOf(p.getInt("reps", 1)));
        edPausa.setText(String.valueOf(p.getLong("pausa", 500)));
        if (ClickService.instance != null) {
            txtServico.setText("✔ Serviço ATIVO  (" + ClickService.instance.totalGravado()
                    + " toques gravados)");
            txtServico.setTextColor(0xFF2E7D32);
        } else {
            txtServico.setText("✖ Serviço DESATIVADO, siga o Passo 1");
            txtServico.setTextColor(0xFFC62828);
        }
    }

    private void salvarConfig() {
        int reps;
        long pausa;
        try { reps = Math.max(0, Integer.parseInt(edReps.getText().toString().trim())); }
        catch (Exception e) { reps = 1; }
        try { pausa = Math.max(0, Long.parseLong(edPausa.getText().toString().trim())); }
        catch (Exception e) { pausa = 500; }
        getSharedPreferences(ClickService.PREFS, MODE_PRIVATE).edit()
                .putInt("reps", reps).putLong("pausa", pausa).apply();
        if (ClickService.instance != null) ClickService.instance.recarregarConfig();
        Toast.makeText(this, "Salvo", Toast.LENGTH_SHORT).show();
    }
}
