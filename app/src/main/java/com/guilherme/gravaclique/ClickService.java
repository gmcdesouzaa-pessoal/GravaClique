package com.guilherme.gravaclique;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Serviço de acessibilidade que:
 *  1) mostra um painel flutuante (REC, parar, play, repetições);
 *  2) durante a gravação, captura cada toque/arraste e repassa ao app de baixo;
 *  3) reproduz a sequência gravada N vezes (0 = infinito).
 */
public class ClickService extends AccessibilityService {

    public static ClickService instance;
    static final String PREFS = "gravaclique";

    /** Uma ação gravada: toque (x1,y1) ou arraste (x1,y1 até x2,y2). */
    static class Acao {
        float x1, y1, x2, y2;
        long espera;   // ms entre o fim da ação anterior e o início desta
        long duracao;  // ms que o dedo ficou na tela

        Acao(float x1, float y1, float x2, float y2, long espera, long duracao) {
            this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2;
            this.espera = espera; this.duracao = duracao;
        }

        boolean ehArraste() { return Math.hypot(x2 - x1, y2 - y1) > 30; }

        String serializar() {
            return x1 + "," + y1 + "," + x2 + "," + y2 + "," + espera + "," + duracao;
        }

        static Acao ler(String s) {
            String[] p = s.split(",");
            return new Acao(Float.parseFloat(p[0]), Float.parseFloat(p[1]),
                    Float.parseFloat(p[2]), Float.parseFloat(p[3]),
                    Long.parseLong(p[4]), Long.parseLong(p[5]));
        }
    }

    private WindowManager wm;
    private final Handler h = new Handler(Looper.getMainLooper());

    private LinearLayout painel;
    private WindowManager.LayoutParams lpPainel;
    private View captura;
    private WindowManager.LayoutParams lpCaptura;
    private TextView txtStatus, txtReps;

    private final List<Acao> gravacao = new ArrayList<>();
    private boolean gravando = false, tocando = false;
    private long fimUltimaAcao;
    private float downX, downY;
    private long downT;

    private int reps = 1;
    private long pausaCiclo = 500;
    private int cicloAtual, indiceAtual;

    // ---------------------------------------------------------------- ciclo de vida

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        carregar();
        mostrarPainel();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }

    @Override
    public void onDestroy() {
        pararTudo();
        esconderPainel();
        instance = null;
        super.onDestroy();
    }

    // ---------------------------------------------------------------- persistência

    private SharedPreferences prefs() { return getSharedPreferences(PREFS, MODE_PRIVATE); }

    private void carregar() {
        SharedPreferences p = prefs();
        reps = p.getInt("reps", 1);
        pausaCiclo = p.getLong("pausa", 500);
        gravacao.clear();
        String s = p.getString("gravacao", "");
        if (!s.isEmpty()) {
            for (String item : s.split(";")) {
                try { gravacao.add(Acao.ler(item)); } catch (Exception ignored) { }
            }
        }
    }

    private void salvar() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < gravacao.size(); i++) {
            if (i > 0) sb.append(';');
            sb.append(gravacao.get(i).serializar());
        }
        prefs().edit().putString("gravacao", sb.toString()).putInt("reps", reps).apply();
    }

    public int totalGravado() { return gravacao.size(); }

    // ---------------------------------------------------------------- painel flutuante

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private TextView botao(String texto, int cor) {
        TextView b = new TextView(this);
        b.setText(texto);
        b.setTextColor(Color.WHITE);
        b.setTextSize(16);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(12), dp(8), dp(12), dp(8));
        GradientDrawable fundo = new GradientDrawable();
        fundo.setColor(cor);
        fundo.setCornerRadius(dp(10));
        b.setBackground(fundo);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        b.setLayoutParams(lp);
        return b;
    }

    public void mostrarPainel() {
        if (painel != null || wm == null) return;

        painel = new LinearLayout(this);
        painel.setOrientation(LinearLayout.VERTICAL);
        painel.setPadding(dp(6), dp(6), dp(6), dp(6));
        GradientDrawable fundo = new GradientDrawable();
        fundo.setColor(0xE0202124);
        fundo.setCornerRadius(dp(14));
        painel.setBackground(fundo);

        LinearLayout linha1 = new LinearLayout(this);
        linha1.setOrientation(LinearLayout.HORIZONTAL);
        TextView bRec = botao("● REC", 0xFFD32F2F);
        TextView bParar = botao("■", 0xFF616161);
        TextView bPlay = botao("▶", 0xFF2E7D32);
        TextView bFechar = botao("✕", 0xFF424242);
        bRec.setOnClickListener(v -> iniciarGravacao());
        bParar.setOnClickListener(v -> pararTudo());
        bPlay.setOnClickListener(v -> tocar());
        bFechar.setOnClickListener(v -> { pararTudo(); esconderPainel(); });
        linha1.addView(bRec); linha1.addView(bParar); linha1.addView(bPlay); linha1.addView(bFechar);

        LinearLayout linha2 = new LinearLayout(this);
        linha2.setOrientation(LinearLayout.HORIZONTAL);
        linha2.setGravity(Gravity.CENTER_VERTICAL);
        TextView bMenos = botao("-", 0xFF455A64);
        TextView bMais = botao("+", 0xFF455A64);
        txtReps = new TextView(this);
        txtReps.setTextColor(Color.WHITE);
        txtReps.setTextSize(15);
        txtReps.setPadding(dp(6), 0, dp(6), 0);
        TextView alca = botao("✥ mover", 0xFF37474F);
        bMenos.setOnClickListener(v -> mudarReps(-1));
        bMais.setOnClickListener(v -> mudarReps(+1));
        bMenos.setOnLongClickListener(v -> { mudarReps(-10); return true; });
        bMais.setOnLongClickListener(v -> { mudarReps(+10); return true; });
        linha2.addView(bMenos); linha2.addView(txtReps); linha2.addView(bMais); linha2.addView(alca);

        txtStatus = new TextView(this);
        txtStatus.setTextColor(0xFFE0E0E0);
        txtStatus.setTextSize(12);
        txtStatus.setPadding(dp(6), dp(2), dp(6), dp(2));

        painel.addView(linha1);
        painel.addView(linha2);
        painel.addView(txtStatus);

        lpPainel = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lpPainel.gravity = Gravity.TOP | Gravity.START;
        lpPainel.x = dp(16);
        lpPainel.y = dp(120);

        // Arrastar o painel pela alça
        alca.setOnTouchListener(new View.OnTouchListener() {
            int ix, iy; float tx, ty;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        ix = lpPainel.x; iy = lpPainel.y; tx = e.getRawX(); ty = e.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        lpPainel.x = ix + (int) (e.getRawX() - tx);
                        lpPainel.y = iy + (int) (e.getRawY() - ty);
                        wm.updateViewLayout(painel, lpPainel);
                        return true;
                }
                return true;
            }
        });

        wm.addView(painel, lpPainel);
        atualizarReps();
        status(gravacao.isEmpty() ? "Toque em REC para gravar"
                : "Gravação salva: " + gravacao.size() + " toques");
    }

    public void esconderPainel() {
        removerCaptura();
        if (painel != null) {
            try { wm.removeView(painel); } catch (Exception ignored) { }
            painel = null;
        }
    }

    private void status(String s) { if (txtStatus != null) txtStatus.setText(s); }

    private void mudarReps(int d) {
        reps = Math.max(0, reps + d);
        prefs().edit().putInt("reps", reps).apply();
        atualizarReps();
    }

    private void atualizarReps() {
        if (txtReps != null) txtReps.setText(reps == 0 ? "∞ vezes" : reps + "x");
    }

    /** Chamado pela tela principal quando o usuário muda as configurações. */
    public void recarregarConfig() {
        SharedPreferences p = prefs();
        reps = p.getInt("reps", 1);
        pausaCiclo = p.getLong("pausa", 500);
        atualizarReps();
    }

    // ---------------------------------------------------------------- gravação

    private void iniciarGravacao() {
        pararTudo();
        gravacao.clear();
        gravando = true;
        fimUltimaAcao = SystemClock.uptimeMillis();

        captura = new View(this);
        captura.setBackgroundColor(0x1AFF0000); // leve tom vermelho: indica que está gravando
        lpCaptura = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lpCaptura.gravity = Gravity.TOP | Gravity.START;

        captura.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX(); downY = e.getRawY(); downT = SystemClock.uptimeMillis();
                    return true;
                case MotionEvent.ACTION_UP:
                    long agora = SystemClock.uptimeMillis();
                    long espera = Math.max(0, downT - fimUltimaAcao);
                    if (gravacao.isEmpty()) espera = Math.min(espera, 300);
                    long dur = Math.max(50, agora - downT);
                    Acao a = new Acao(downX, downY, e.getRawX(), e.getRawY(), espera, dur);
                    gravacao.add(a);
                    fimUltimaAcao = agora;
                    status("Gravando... " + gravacao.size() + " toques  (■ para parar)");
                    repassar(a);
                    return true;
            }
            return true;
        });

        // A camada de captura fica embaixo do painel, para os botões continuarem funcionando
        try { wm.removeView(painel); } catch (Exception ignored) { }
        wm.addView(captura, lpCaptura);
        wm.addView(painel, lpPainel);
        status("Gravando... toque na tela normalmente");
    }

    /** Deixa a camada "transparente ao toque", executa o mesmo toque no app de baixo e volta a capturar. */
    private void repassar(Acao a) {
        if (captura == null) return;
        lpCaptura.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        wm.updateViewLayout(captura, lpCaptura);
        executar(a, () -> h.postDelayed(() -> {
            if (captura != null && gravando) {
                lpCaptura.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                wm.updateViewLayout(captura, lpCaptura);
            }
        }, 60));
    }

    private void removerCaptura() {
        if (captura != null) {
            try { wm.removeView(captura); } catch (Exception ignored) { }
            captura = null;
        }
    }

    private void pararGravacao() {
        gravando = false;
        removerCaptura();
        salvar();
        status("Gravado: " + gravacao.size() + " toques. Escolha as vezes e ▶");
    }

    // ---------------------------------------------------------------- reprodução

    private void tocar() {
        if (gravando) pararGravacao();
        if (tocando) return;
        if (gravacao.isEmpty()) {
            Toast.makeText(this, "Nada gravado ainda", Toast.LENGTH_SHORT).show();
            return;
        }
        recarregarConfig();
        tocando = true;
        cicloAtual = 1;
        indiceAtual = 0;
        proximo();
    }

    private void proximo() {
        if (!tocando) return;
        if (indiceAtual >= gravacao.size()) {
            if (reps > 0 && cicloAtual >= reps) {
                tocando = false;
                status("Concluído: " + reps + "x");
                return;
            }
            cicloAtual++;
            indiceAtual = 0;
            h.postDelayed(this::proximo, pausaCiclo);
            return;
        }
        final Acao a = gravacao.get(indiceAtual);
        status("Repetição " + cicloAtual + "/" + (reps == 0 ? "∞" : reps)
                + "  toque " + (indiceAtual + 1) + "/" + gravacao.size());
        h.postDelayed(() -> {
            if (!tocando) return;
            executar(a, () -> { indiceAtual++; proximo(); });
        }, a.espera);
    }

    private void pararTudo() {
        if (gravando) pararGravacao();
        if (tocando) {
            tocando = false;
            h.removeCallbacksAndMessages(null);
            status("Parado");
        }
    }

    // ---------------------------------------------------------------- execução do gesto

    private void executar(Acao a, Runnable fim) {
        Path p = new Path();
        p.moveTo(Math.max(0, a.x1), Math.max(0, a.y1));
        if (a.ehArraste()) p.lineTo(Math.max(0, a.x2), Math.max(0, a.y2));
        long dur = Math.min(a.duracao, 10000);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, dur))
                .build();
        boolean ok = dispatchGesture(g, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription d) { fim.run(); }
            @Override public void onCancelled(GestureDescription d) { fim.run(); }
        }, h);
        if (!ok) fim.run();
    }
}
