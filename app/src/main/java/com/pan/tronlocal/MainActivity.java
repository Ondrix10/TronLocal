package com.pan.tronlocal;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.*;
import android.view.*;
import android.content.Context;
import java.util.*;

public class MainActivity extends Activity {

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(new GameView(this));
        requestImmersiveFullscreen();
    }

    private void requestImmersiveFullscreen() {
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
        getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }
}

class GameView extends View {

    enum Mode { MENU, COUNTDOWN, PLAYING, ROUND_OVER, MATCH_OVER }
    enum GameType { MULTI, SOLO }

    static class Player {
        float x, y, angle;
        int color, id;
        boolean alive = true;
        boolean left = false, right = false;
        boolean ai = false;
        long nextAiMs;

        Player(int id, int color) {
            this.id = id;
            this.color = color;
        }
    }

    static final int[] COLORS = {
            Color.rgb(255, 70, 70),
            Color.rgb(70, 145, 255),
            Color.rgb(65, 225, 115),
            Color.rgb(255, 220, 65)
    };

    final Paint p = new Paint();
    final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Random rnd = new Random();

    Bitmap trail;
    Canvas trailCanvas;

    Mode mode = Mode.MENU;
    GameType gameType = GameType.MULTI;

    Player[] players = new Player[4];

    int activePlayers = 2;
    int targetScore = 10;
    int speedLevel = 2;
    int difficulty = 2;

    int[] scores = new int[4];
    ArrayList<Integer> deathOrder = new ArrayList<>();

    long lastFrame;
    long countdownUntil;
    long roundOverUntil;

    float speed = 200f;
    float turnSpeed = (float)Math.toRadians(185);
    float dt;

    long[] nextGapMs = new long[4];
    long[] gapUntilMs = new long[4];

    int width, height;
    int gameL, gameT, gameR, gameB;
    int controlTop;

    int menuPage = 0;

    GameView(Context c) {
        super(c);
        setBackgroundColor(Color.BLACK);
        setFocusable(true);
        text.setTypeface(Typeface.MONOSPACE);
        text.setTextAlign(Paint.Align.CENTER);
        p.setAntiAlias(false);
    }

    void layoutGame() {
        width = getWidth();
        height = getHeight();

        int shortSide = Math.max(1, Math.min(width, height));

        // Spodní ovládací panel. Herní plocha je nad ním.
        controlTop = height - Math.max(125, Math.round(shortSide * 0.22f));

        int margin = Math.max(8, Math.round(shortSide * 0.018f));

        gameL = margin;
        gameR = width - margin;
        gameT = margin + 45;
        gameB = controlTop - margin;

        if (gameB <= gameT + 100) {
            gameT = height / 8;
            gameB = height * 2 / 3;
            controlTop = gameB + margin;
        }

        if (trail == null
                || trail.getWidth() != width
                || trail.getHeight() != height) {

            trail = Bitmap.createBitmap(
                    width,
                    height,
                    Bitmap.Config.ARGB_8888
            );
            trailCanvas = new Canvas(trail);
            trail.eraseColor(Color.BLACK);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        trail = null;
        layoutGame();
    }

    int totalPlayers() {
        return gameType == GameType.MULTI
                ? activePlayers
                : activePlayers + 1;
    }

    void applySettings() {
        speed = speedLevel == 1 ? 145f
                : speedLevel == 2 ? 200f
                : 265f;

        turnSpeed = (float)Math.toRadians(
                speedLevel == 1 ? 160
                        : speedLevel == 2 ? 185
                        : 210
        );
    }

    void startRound() {
        layoutGame();
        applySettings();

        trail.eraseColor(Color.BLACK);
        deathOrder.clear();

        int count = totalPlayers();

        for (int i = 0; i < count; i++) {
            players[i] = new Player(i, COLORS[i]);
            players[i].ai = gameType == GameType.SOLO && i > 0;
        }

        for (int i = count; i < 4; i++) {
            players[i] = null;
        }

        long now = System.currentTimeMillis();

        for (int i = 0; i < count; i++) {
            boolean placed = false;

            for (int tries = 0; tries < 300 && !placed; tries++) {
                float x = gameL + 45
                        + rnd.nextFloat() * Math.max(1, gameR - gameL - 90);
                float y = gameT + 45
                        + rnd.nextFloat() * Math.max(1, gameB - gameT - 90);

                placed = true;

                for (int j = 0; j < i; j++) {
                    float dx = x - players[j].x;
                    float dy = y - players[j].y;
                    if (dx * dx + dy * dy < 150 * 150) {
                        placed = false;
                        break;
                    }
                }

                if (placed) {
                    players[i].x = x;
                    players[i].y = y;
                }
            }

            players[i].angle =
                    rnd.nextFloat() * (float)(Math.PI * 2);
            players[i].alive = true;
            players[i].left = false;
            players[i].right = false;

            // Normální souvislá čára. První díra přijde až po několika sekundách.
            nextGapMs[i] = now + 2800 + rnd.nextInt(1800);
            gapUntilMs[i] = 0;

            players[i].nextAiMs = now + 150 + rnd.nextInt(200);
        }

        mode = Mode.COUNTDOWN;
        countdownUntil = now + 1600;
        lastFrame = System.nanoTime();
        invalidate();
    }

    void startMatch() {
        Arrays.fill(scores, 0);
        startRound();
    }

    boolean solidAt(int x, int y) {
        if (x < gameL || x >= gameR || y < gameT || y >= gameB) {
            return true;
        }
        return trail.getPixel(x, y) != Color.BLACK;
    }

    boolean collisionAhead(Player q) {
        // Nekontrolujeme pixel přímo pod hlavou:
        // ten patří právě nakreslené vlastní stopě.
        float ca = (float)Math.cos(q.angle);
        float sa = (float)Math.sin(q.angle);

        // Několik bodů před hlavou zachytí i rychlý průjezd přes čáru.
        int[] distances = { 5, 10, 15, 20 };

        for (int d : distances) {
            int x = Math.round(q.x + ca * d);
            int y = Math.round(q.y + sa * d);

            if (solidAt(x, y)) return true;

            // Malý boční vzorek kvůli 4px stopě a rychlému pohybu.
            int sx = Math.round(-sa * 2.5f);
            int sy = Math.round(ca * 2.5f);

            if (solidAt(x + sx, y + sy)
                    || solidAt(x - sx, y - sy)) {
                return true;
            }
        }

        return false;
    }

    void drawSegment(Player pl, float x0, float y0, float x1, float y1) {
        p.setColor(pl.color);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(4f);
        p.setStrokeCap(Paint.Cap.SQUARE);
        p.setAntiAlias(false);
        trailCanvas.drawLine(x0, y0, x1, y1, p);
    }

    void update() {
        if (mode == Mode.COUNTDOWN) {
            if (System.currentTimeMillis() >= countdownUntil) {
                mode = Mode.PLAYING;
                lastFrame = System.nanoTime();
            }
            return;
        }

        if (mode != Mode.PLAYING) return;

        long nowNano = System.nanoTime();
        dt = Math.min(0.033f,
                (nowNano - lastFrame) / 1_000_000_000f);
        lastFrame = nowNano;

        long ms = System.currentTimeMillis();
        int count = totalPlayers();

        for (int i = 0; i < count; i++) {
            Player q = players[i];
            if (q == null || !q.alive) continue;

            if (q.ai) updateAI(q, ms);

            if (q.left) q.angle -= turnSpeed * dt;
            if (q.right) q.angle += turnSpeed * dt;

            float nx = q.x
                    + (float)Math.cos(q.angle) * speed * dt;
            float ny = q.y
                    + (float)Math.sin(q.angle) * speed * dt;

            boolean gap = ms < gapUntilMs[i];

            if (ms >= nextGapMs[i] && ms >= gapUntilMs[i]) {
                // Jen občasná krátká mezera, ne nepřetržité mezery.
                gapUntilMs[i] = ms + 180 + rnd.nextInt(150);
                nextGapMs[i] = gapUntilMs[i]
                        + 2300 + rnd.nextInt(2300);
                gap = true;
            }

            if (!gap && collisionAhead(q)) {
                kill(i);
                continue;
            }

            if (!gap) {
                drawSegment(q, q.x, q.y, nx, ny);
            }

            q.x = nx;
            q.y = ny;
        }

        int alive = 0;
        int last = -1;

        for (int i = 0; i < count; i++) {
            if (players[i] != null && players[i].alive) {
                alive++;
                last = i;
            }
        }

        if (alive <= 1) {
            if (alive == 1 && last >= 0) {
                // Poslední přeživší dostane počet bodů odpovídající pořadí.
                scores[last] += count - 1;
                deathOrder.add(last);
            }

            mode = Mode.ROUND_OVER;
            roundOverUntil = System.currentTimeMillis() + 1700;
        }
    }

    void kill(int i) {
        if (i < 0 || i >= players.length
                || players[i] == null
                || !players[i].alive) return;

        players[i].alive = false;
        players[i].left = false;
        players[i].right = false;

        deathOrder.add(i);
        scores[i] += deathOrder.size() - 1;
    }

    void updateAI(Player q, long now) {
        if (now < q.nextAiMs) return;

        long delay = difficulty == 1 ? 260
                : difficulty == 2 ? 130
                : 65;
        q.nextAiMs = now + delay;

        float[] turns = {-1f, 0f, 1f};
        float best = -Float.MAX_VALUE;
        float bestTurn = 0;

        for (float t : turns) {
            float a = q.angle + t * (float)Math.toRadians(
                    difficulty == 1 ? 38
                            : difficulty == 2 ? 48
                            : 58
            );

            float score = spaceScore(q, a);

            if (difficulty >= 2) {
                score += openSideScore(q, a) * 0.35f;
            }

            if (difficulty == 3) {
                score += attackScore(q, a) * 0.55f;
            }

            score += rnd.nextFloat() *
                    (difficulty == 1 ? 30
                            : difficulty == 2 ? 10 : 3);

            if (score > best) {
                best = score;
                bestTurn = t;
            }
        }

        q.left = bestTurn < 0;
        q.right = bestTurn > 0;
    }

    float spaceScore(Player q, float a) {
        int max = difficulty == 1 ? 110
                : difficulty == 2 ? 170
                : 240;

        float score = 0;

        for (int d = 12; d <= max; d += 10) {
            int x = Math.round(
                    q.x + (float)Math.cos(a) * d);
            int y = Math.round(
                    q.y + (float)Math.sin(a) * d);

            if (solidAt(x, y)) break;
            score += 1f;
        }

        if (difficulty >= 2) {
            for (int side = -1; side <= 1; side += 2) {
                float aa = a + side * (float)Math.toRadians(18);

                for (int d = 20; d <= 110; d += 20) {
                    int x = Math.round(
                            q.x + (float)Math.cos(aa) * d);
                    int y = Math.round(
                            q.y + (float)Math.sin(aa) * d);

                    if (solidAt(x, y)) break;
                    score += 0.18f;
                }
            }
        }

        return score;
    }

    float openSideScore(Player q, float a) {
        float left = a - (float)Math.PI / 2f;
        float right = a + (float)Math.PI / 2f;
        return spaceScore(q, left) * 0.5f
                + spaceScore(q, right) * 0.5f;
    }

    float attackScore(Player q, float a) {
        float best = 0;
        int count = totalPlayers();

        for (int i = 0; i < count; i++) {
            Player target = players[i];

            if (target == null || target == q || !target.alive) continue;

            float dx = target.x - q.x;
            float dy = target.y - q.y;
            float dist = (float)Math.sqrt(dx * dx + dy * dy);

            if (dist < 1) continue;

            float targetAngle = (float)Math.atan2(dy, dx);
            float diff = Math.abs(
                    normalizeAngle(targetAngle - a));

            float value = (float)Math.max(
                    0, 900 - dist * 2)
                    * (1f - diff / (float)Math.PI);

            best = Math.max(best, value / 40f);
        }

        return best;
    }

    float normalizeAngle(float a) {
        while (a > Math.PI) a -= 2 * Math.PI;
        while (a < -Math.PI) a += 2 * Math.PI;
        return a;
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        layoutGame();
        update();

        c.drawColor(Color.BLACK);

        if (mode == Mode.MENU) {
            drawMenu(c);
            invalidate();
            return;
        }

        c.drawBitmap(trail, 0, 0, p);

        p.setStyle(Paint.Style.FILL);
        p.setAntiAlias(false);

        int count = totalPlayers();

        for (int i = 0; i < count; i++) {
            if (players[i] != null && players[i].alive) {
                p.setColor(players[i].color);
                c.drawRect(
                        players[i].x - 4,
                        players[i].y - 4,
                        players[i].x + 5,
                        players[i].y + 5,
                        p
                );
            }
        }

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2);
        p.setColor(Color.WHITE);

        c.drawRect(gameL, gameT, gameR, gameB, p);

        drawHud(c);
        drawControls(c);

        if (mode == Mode.COUNTDOWN) {
            int n = (int)Math.ceil(
                    (countdownUntil - System.currentTimeMillis()) / 500.0);

            text.setTextSize(Math.min(width, height) * .18f);
            text.setColor(Color.WHITE);

            c.drawText(
                    n > 0 ? "" + n : "START",
                    width / 2f,
                    (gameT + gameB) / 2f,
                    text
            );

            invalidate();

        } else if (mode == Mode.ROUND_OVER) {
            drawOverlay(c, "KONEC KOLA", "Další kolo...");

            if (System.currentTimeMillis() >= roundOverUntil) {
                finishRound();
            } else {
                invalidate();
            }

        } else if (mode == Mode.MATCH_OVER) {
            drawMatchOver(c);
        } else {
            invalidate();
        }
    }

    void drawHud(Canvas c) {
        text.setTypeface(Typeface.MONOSPACE);
        text.setTextSize(Math.max(
                18, Math.min(width, height) * .036f));

        int count = totalPlayers();

        for (int i = 0; i < count; i++) {
            if (players[i] == null) continue;

            text.setColor(players[i].color);

            String s = (i == 0 && gameType == GameType.SOLO
                    ? "TY  " : "H" + (i + 1) + "  ") + scores[i];

            float x = width * (i + 1f) / (count + 1f);
            c.drawText(s, x, gameT - 12, text);
        }
    }

    /*
     * NOVÉ OVLÁDÁNÍ:
     * - SOLO: dva velké ovladače vlevo/vpravo dole.
     * - 2 hráči: hráč 1 má celý levý ovladač, hráč 2 celý pravý.
     *   Každý má uvnitř vlevo VLEVO a vpravo VPRAVO.
     * - 3/4 hráči: každý hráč dostane vlastní kompaktní sloupec dole.
     */
    void drawControls(Canvas c) {
        int humanCount = gameType == GameType.SOLO ? 1 : totalPlayers();

        for (int i = 0; i < humanCount; i++) {
            if (i >= players.length || players[i] == null) continue;
            drawPlayerControls(c, i, humanCount);
        }
    }

    void drawPlayerControls(Canvas c, int playerIndex, int humanCount) {
        float colLeft;
        float colRight;

        if (gameType == GameType.SOLO) {
            colLeft = 0;
            colRight = width;
        } else if (humanCount == 2) {
            // Hráč 1 vlevo, hráč 2 vpravo.
            float half = width / 2f;
            colLeft = playerIndex == 0 ? 0 : half;
            colRight = playerIndex == 0 ? half : width;
        } else {
            // 3 nebo 4 hráči: rovnoměrné sloupce.
            float w = width / (float)humanCount;
            colLeft = playerIndex * w;
            colRight = (playerIndex + 1) * w;
        }

        float pad = Math.max(6, Math.min(width, height) * .012f);
        float top = controlTop + pad;
        float bottom = height - pad;
        float mid = (colLeft + colRight) / 2f;

        // Levé tlačítko.
        drawButton(
                c,
                colLeft + pad,
                top,
                mid - pad / 2f,
                bottom,
                "◀",
                "VLEVO",
                players[playerIndex].color
        );

        // Pravé tlačítko.
        drawButton(
                c,
                mid + pad / 2f,
                top,
                colRight - pad,
                bottom,
                "▶",
                "VPRAVO",
                players[playerIndex].color
        );

        text.setTextSize(Math.max(
                12, Math.min(width, height) *
                        (humanCount >= 3 ? .020f : .025f)));
        text.setColor(players[playerIndex].color);

        c.drawText(
                gameType == GameType.SOLO
                        ? "TY"
                        : "H" + (playerIndex + 1),
                mid,
                top + 16,
                text
        );
    }

    void drawButton(
            Canvas c,
            float left,
            float top,
            float right,
            float bottom,
            String symbol,
            String label,
            int playerColor
    ) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.argb(28, 255, 255, 255));
        c.drawRect(left, top, right, bottom, p);

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2);
        p.setColor(Color.argb(190, 255, 255, 255));
        c.drawRect(left, top, right, bottom, p);

        text.setTextSize(Math.max(
                22, Math.min(width, height) * .055f));
        text.setColor(playerColor);

        float cx = (left + right) / 2f;
        float cy = (top + bottom) / 2f - 4;

        c.drawText(symbol, cx, cy, text);

        text.setTextSize(Math.max(
                11, Math.min(width, height) * .018f));
        text.setColor(Color.WHITE);

        c.drawText(label, cx, bottom - 10, text);
    }

    void finishRound() {
        int count = totalPlayers();

        for (int i = 0; i < count; i++) {
            if (scores[i] >= targetScore) {
                mode = Mode.MATCH_OVER;
                return;
            }
        }

        startRound();
    }

    void drawOverlay(Canvas c, String title, String sub) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.argb(205, 0, 0, 0));
        c.drawRect(0, 0, width, height, p);

        text.setColor(Color.WHITE);
        text.setTextSize(Math.min(width, height) * .075f);

        c.drawText(title, width / 2f, height * .45f, text);

        text.setTextSize(Math.min(width, height) * .032f);
        c.drawText(sub, width / 2f, height * .55f, text);
    }

    void drawMatchOver(Canvas c) {
        drawOverlay(
                c,
                "ZÁPAS SKONČIL",
                "Klepni pro návrat do menu"
        );

        int count = totalPlayers();

        text.setTextSize(Math.min(width, height) * .04f);

        float y = height * .64f;

        for (int i = 0; i < count; i++) {
            if (players[i] == null) continue;

            text.setColor(players[i].color);

            c.drawText(
                    (i == 0 && gameType == GameType.SOLO
                            ? "TY" : "H" + (i + 1))
                            + "  " + scores[i],
                    width / 2f,
                    y,
                    text
            );

            y += Math.min(width, height) * .055f;
        }
    }

    void drawMenu(Canvas c) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.BLACK);
        c.drawRect(0, 0, width, height, p);

        text.setColor(Color.WHITE);
        text.setTypeface(Typeface.MONOSPACE);

        text.setTextSize(Math.min(width, height) * .10f);
        c.drawText("TRON", width / 2f, height * .11f, text);

        text.setTextSize(Math.min(width, height) * .055f);
        c.drawText("LOCAL", width / 2f, height * .17f, text);

        if (menuPage == 0) {
            drawMainMenu(c);
        } else if (menuPage == 1) {
            drawMultiMenu(c);
        } else {
            drawSoloMenu(c);
        }
    }

    void drawMainMenu(Canvas c) {
        float y = height * .28f;

        drawBigButton(c, y, "HRA PRO VÍCE HRÁČŮ", gameType == GameType.MULTI);
        drawBigButton(c, y + height * .16f,
                "HRA PRO JEDNOHO", gameType == GameType.SOLO);

        text.setColor(Color.argb(150, 255, 255, 255));
        text.setTextSize(Math.min(width, height) * .026f);

        c.drawText(
                "2–4 hráči na jednom telefonu",
                width / 2f, height * .79f, text);

        c.drawText(
                "Dotykové ovládání • bez internetu",
                width / 2f, height * .84f, text);

        drawBigButton(c, height * .90f, "POKRAČOVAT", false);
    }

    void drawMultiMenu(Canvas c) {
        text.setTextSize(Math.min(width, height) * .045f);
        text.setColor(Color.WHITE);

        c.drawText(
                "HRA PRO VÍCE HRÁČŮ",
                width / 2f, height * .24f, text);

        drawOption(c, height * .34f,
                "POČET HRÁČŮ", activePlayers + " hráči");

        drawOption(c, height * .49f,
                "BODY DO VÍTĚZSTVÍ", targetScore + " bodů");

        drawOption(c, height * .64f,
                "RYCHLOST", speedName());

        drawBigButton(c, height * .80f, "SPUSTIT HRU", false);
        drawSmallBack(c, height * .91f);
    }

    void drawSoloMenu(Canvas c) {
        text.setTextSize(Math.min(width, height) * .045f);
        text.setColor(Color.WHITE);

        c.drawText(
                "HRA PRO JEDNOHO",
                width / 2f, height * .24f, text);

        drawOption(c, height * .34f,
                "POČET PROTIVNÍKŮ",
                activePlayers + "  (celkem " + (activePlayers + 1) + ")");

        drawOption(c, height * .47f,
                "OBTÍŽNOST", difficultyName());

        drawOption(c, height * .60f,
                "BODY DO VÍTĚZSTVÍ", targetScore + " bodů");

        drawOption(c, height * .73f,
                "RYCHLOST", speedName());

        drawBigButton(c, height * .86f, "SPUSTIT HRU", false);
        drawSmallBack(c, height * .95f);
    }

    String speedName() {
        return speedLevel == 1 ? "POMALÁ"
                : speedLevel == 2 ? "NORMÁLNÍ"
                : "RYCHLÁ";
    }

    String difficultyName() {
        return difficulty == 1 ? "LEHKÁ"
                : difficulty == 2 ? "STŘEDNÍ"
                : "TĚŽKÁ";
    }

    void drawOption(Canvas c, float cy, String label, String value) {
        float w = Math.min(width * .86f, 650);
        float h = Math.max(
                64, Math.min(width, height) * .085f);

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2);
        p.setColor(Color.WHITE);

        c.drawRect(
                width / 2f - w / 2,
                cy - h / 2,
                width / 2f + w / 2,
                cy + h / 2,
                p);

        text.setTextSize(Math.min(width, height) * .023f);
        text.setColor(Color.argb(170, 255, 255, 255));

        c.drawText(label, width / 2f, cy - 7, text);

        text.setTextSize(Math.min(width, height) * .032f);
        text.setColor(Color.WHITE);

        c.drawText(value, width / 2f, cy + 22, text);
    }

    void drawBigButton(Canvas c, float cy, String label, boolean selected) {
        float w = Math.min(width * .82f, 650);
        float h = Math.max(
                66, Math.min(width, height) * .09f);

        p.setStyle(Paint.Style.FILL);
        p.setColor(selected ? Color.WHITE : Color.BLACK);

        c.drawRect(
                width / 2f - w / 2,
                cy - h / 2,
                width / 2f + w / 2,
                cy + h / 2,
                p);

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2);
        p.setColor(Color.WHITE);

        c.drawRect(
                width / 2f - w / 2,
                cy - h / 2,
                width / 2f + w / 2,
                cy + h / 2,
                p);

        text.setTextSize(Math.min(width, height) * .032f);
        text.setColor(selected ? Color.BLACK : Color.WHITE);

        c.drawText(label, width / 2f, cy + 11, text);
    }

    void drawSmallBack(Canvas c, float cy) {
        text.setColor(Color.argb(180, 255, 255, 255));
        text.setTextSize(Math.min(width, height) * .028f);
        c.drawText("ZPĚT", width / 2f, cy, text);
    }

    boolean inRect(float x, float y, float top, float bottom) {
        return y > top && y < bottom;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX();
        float y = e.getY();
        int action = e.getActionMasked();

        if (mode == Mode.MENU) {
            if (action != MotionEvent.ACTION_UP) return true;

            if (menuPage == 0) {
                if (inRect(x, y, height * .20f, height * .38f)) {
                    gameType = GameType.MULTI;
                    menuPage = 1;
                    invalidate();
                    return true;
                }

                if (inRect(x, y, height * .38f, height * .55f)) {
                    gameType = GameType.SOLO;
                    menuPage = 2;
                    invalidate();
                    return true;
                }

                if (inRect(x, y, height * .84f, height * .97f)) {
                    gameType = GameType.MULTI;
                    menuPage = 1;
                    invalidate();
                    return true;
                }
            } else if (menuPage == 1) {
                if (inRect(x, y, height * .29f, height * .40f)) {
                    activePlayers = activePlayers >= 4 ? 2 : activePlayers + 1;
                    invalidate();
                    return true;
                }

                if (inRect(x, y, height * .43f, height * .55f)) {
                    targetScore = targetScore == 50 ? 5
                            : targetScore == 5 ? 10
                            : targetScore == 10 ? 20 : 50;
                    invalidate();
                    return true;
                }

                if (inRect(x, y, height * .58f, height * .70f)) {
                    speedLevel = speedLevel == 3 ? 1 : speedLevel + 1;
                    invalidate();
                    return true;
                }

                if (inRect(x, y, height * .74f, height * .88f)) {
                    startMatch();
                    return true;
                }

                if (y > height * .88f) {
                    menuPage = 0;
                    invalidate();
                    return true;
                }
            } else {
                if (inRect(x, y, height * .29f, height * .40f)) {
                    activePlayers = activePlayers >= 3 ? 1 : activePlayers + 1;
                    invalidate();
                    return true;
                }

                if (inRect(x, y, height * .42f, height * .53f)) {
                    difficulty = difficulty >= 3 ? 1 : difficulty + 1;
                    invalidate();
                    return true;
                }

                if (inRect(x, y, height * .55f, height * .66f)) {
                    targetScore = targetScore == 50 ? 5
                            : targetScore == 5 ? 10
                            : targetScore == 10 ? 20 : 50;
                    invalidate();
                    return true;
                }

                if (inRect(x, y, height * .68f, height * .80f)) {
                    speedLevel = speedLevel == 3 ? 1 : speedLevel + 1;
                    invalidate();
                    return true;
                }

                if (inRect(x, y, height * .81f, height * .92f)) {
                    startMatch();
                    return true;
                }

                if (y > height * .91f) {
                    menuPage = 0;
                    invalidate();
                    return true;
                }
            }

            return true;
        }

        if (mode == Mode.MATCH_OVER
                && action == MotionEvent.ACTION_UP) {
            mode = Mode.MENU;
            menuPage = 0;
            invalidate();
            return true;
        }

        if (mode == Mode.PLAYING || mode == Mode.COUNTDOWN) {
            int idx = controlPlayer(x, y);

            if (idx >= 0
                    && idx < players.length
                    && players[idx] != null
                    && !players[idx].ai) {

                int button = controlButton(x, y, idx);

                if (button >= 0) {
                    if (action == MotionEvent.ACTION_DOWN
                            || action == MotionEvent.ACTION_MOVE) {
                        players[idx].left = button == 0;
                        players[idx].right = button == 1;
                    } else if (action == MotionEvent.ACTION_UP
                            || action == MotionEvent.ACTION_CANCEL) {
                        players[idx].left = false;
                        players[idx].right = false;
                    }
                }

                return true;
            }
        }

        return true;
    }

    int controlPlayer(float x, float y) {
        if (y < controlTop) return -1;

        int count = totalPlayers();

        if (gameType == GameType.SOLO) {
            return 0;
        }

        if (count == 2) {
            return x < width / 2f ? 0 : 1;
        }

        int idx = (int)(x / (width / (float)count));
        return Math.max(0, Math.min(count - 1, idx));
    }

    int controlButton(float x, float y, int playerIndex) {
        if (y < controlTop) return -1;

        int count = totalPlayers();

        float left, right;

        if (gameType == GameType.SOLO) {
            left = 0;
            right = width;
        } else if (count == 2) {
            float half = width / 2f;
            left = playerIndex == 0 ? 0 : half;
            right = playerIndex == 0 ? half : width;
        } else {
            float w = width / (float)count;
            left = playerIndex * w;
            right = (playerIndex + 1) * w;
        }

        float mid = (left + right) / 2f;
        return x < mid ? 0 : 1;
    }
}
