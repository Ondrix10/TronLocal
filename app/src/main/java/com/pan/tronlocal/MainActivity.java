package com.pan.tronlocal;

import android.app.Activity;
import android.os.Bundle;
import android.os.Build;
import android.graphics.*;
import android.view.*;
import android.content.Context;
import java.util.*;

public class MainActivity extends Activity {
    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        requestImmersiveFullscreen();
        setContentView(new GameView(this));
    }

    private void requestImmersiveFullscreen() {
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setNavigationBarColor(Color.BLACK);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            getWindow().getInsetsController().hide(android.view.WindowInsets.Type.statusBars() | android.view.WindowInsets.Type.navigationBars());
            getWindow().getInsetsController().setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }
}

class GameView extends View {
    enum Mode { MENU, COUNTDOWN, PLAYING, ROUND_OVER, MATCH_OVER }
    enum GameType { MULTI, SOLO }

    static class Player {
        float x, y, angle;
        int color, id;
        boolean alive, left, right, ai;
        long nextAiMs;
        Player(int id, int color) { this.id=id; this.color=color; this.alive=true; }
    }

    static final int[] COLORS = {
            Color.rgb(255,70,70), Color.rgb(70,145,255), Color.rgb(65,225,115), Color.rgb(255,220,65)
    };

    final Paint p = new Paint();
    final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Random rnd = new Random();
    Bitmap trail;
    Canvas trailCanvas;
    Mode mode = Mode.MENU;
    GameType gameType = GameType.MULTI;
    Player[] players = new Player[4];
    int activePlayers = 2;       // multiplayer total, solo = 1 human + opponents
    int targetScore = 10;
    int speedLevel = 2;          // 1 slow, 2 normal, 3 fast
    int difficulty = 2;          // 1 easy, 2 normal, 3 hard
    int[] scores = new int[4];
    ArrayList<Integer> deathOrder = new ArrayList<>();

    long lastFrame, countdownUntil, roundOverUntil;
    float speed = 190f;
    float turnSpeed = (float)Math.toRadians(185);
    float dt;
    long[] nextGapMs = new long[4];
    long[] gapUntilMs = new long[4];
    int width, height, gameL, gameT, gameR, gameB;
    int menuPage = 0;
    int menuScroll = 0;

    GameView(Context c) {
        super(c);
        setBackgroundColor(Color.BLACK);
        setFocusable(true);
        text.setTypeface(Typeface.MONOSPACE);
        text.setTextAlign(Paint.Align.CENTER);
        p.setAntiAlias(false);
    }

    void layoutGame() {
        width = getWidth(); height = getHeight();
        int shortSide = Math.max(1, Math.min(width, height));
        int controlH = Math.max(120, Math.round(shortSide * 0.19f));
        int margin = Math.max(8, Math.round(shortSide * 0.018f));
        gameL = margin;
        gameR = width - margin;
        gameT = controlH + margin;
        gameB = height - controlH - margin;
        if (gameB <= gameT + 100) { gameT = height/5; gameB = height - height/5; }
        if (trail == null || trail.getWidth()!=width || trail.getHeight()!=height) {
            trail = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            trailCanvas = new Canvas(trail);
            trail.eraseColor(Color.BLACK);
        }
    }

    @Override protected void onSizeChanged(int w,int h,int ow,int oh) { super.onSizeChanged(w,h,ow,oh); trail=null; layoutGame(); }

    int totalPlayers() { return gameType==GameType.MULTI ? activePlayers : activePlayers + 1; }

    void applySettings() {
        speed = speedLevel==1 ? 145f : speedLevel==2 ? 200f : 265f;
        turnSpeed = (float)Math.toRadians(speedLevel==1 ? 160 : speedLevel==2 ? 185 : 210);
    }

    void startRound() {
        layoutGame(); applySettings();
        trail.eraseColor(Color.BLACK);
        deathOrder.clear();
        int count = totalPlayers();
        for (int i=0;i<count;i++) {
            players[i] = new Player(i, COLORS[i]);
            players[i].ai = gameType==GameType.SOLO && i>0;
        }
        for (int i=count;i<4;i++) players[i]=null;

        for (int i=0;i<count;i++) {
            boolean placed=false;
            for (int tries=0; tries<200 && !placed; tries++) {
                float x=gameL+55+rnd.nextFloat()*Math.max(1,gameR-gameL-110);
                float y=gameT+55+rnd.nextFloat()*Math.max(1,gameB-gameT-110);
                placed=true;
                for(int j=0;j<i;j++) {
                    float dx=x-players[j].x, dy=y-players[j].y;
                    if(dx*dx+dy*dy < 120*120) { placed=false; break; }
                }
                if(placed) { players[i].x=x; players[i].y=y; }
            }
            players[i].angle=rnd.nextFloat()*(float)(Math.PI*2);
            players[i].alive=true; players[i].left=false; players[i].right=false;
            long now=System.currentTimeMillis();
            nextGapMs[i]=now + 2400 + rnd.nextInt(1800);
            gapUntilMs[i]=0;
            players[i].nextAiMs=now+250+rnd.nextInt(250);
        }
        mode=Mode.COUNTDOWN;
        countdownUntil=System.currentTimeMillis()+1600;
        lastFrame=System.nanoTime();
        invalidate();
    }

    void startMatch() { Arrays.fill(scores,0); startRound(); }

    boolean solidAt(int x,int y) {
        if(x<gameL || x>=gameR || y<gameT || y>=gameB) return true;
        return trail.getPixel(x,y) != Color.BLACK;
    }

    void drawSegment(Player pl,float x0,float y0,float x1,float y1) {
        p.setColor(pl.color); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(4f);
        p.setStrokeCap(Paint.Cap.SQUARE); p.setAntiAlias(false);
        trailCanvas.drawLine(x0,y0,x1,y1,p);
    }

    void update() {
        if(mode==Mode.COUNTDOWN) { if(System.currentTimeMillis()>=countdownUntil) mode=Mode.PLAYING; return; }
        if(mode!=Mode.PLAYING) return;
        long now=System.nanoTime();
        dt=Math.min(.033f,(now-lastFrame)/1_000_000_000f); lastFrame=now;
        long ms=System.currentTimeMillis();
        int count=totalPlayers();

        for(int i=0;i<count;i++) if(players[i]!=null && players[i].alive) {
            Player q=players[i];
            if(q.ai) updateAI(q, ms);
            if(q.left) q.angle-=turnSpeed*dt;
            if(q.right) q.angle+=turnSpeed*dt;
            float nx=q.x+(float)Math.cos(q.angle)*speed*dt;
            float ny=q.y+(float)Math.sin(q.angle)*speed*dt;

            boolean gap = ms < gapUntilMs[i];
            if(ms>=nextGapMs[i] && ms>=gapUntilMs[i]) {
                gapUntilMs[i]=ms + 170 + rnd.nextInt(150); // occasional gap, normally trail remains solid
                nextGapMs[i]=gapUntilMs[i] + 2100 + rnd.nextInt(1900);
                gap=true;
            }

            // Probe several pixels ahead. This avoids false self-collisions while still making the trail solid.
            float probe=8f;
            int px=Math.round(q.x+(float)Math.cos(q.angle)*probe);
            int py=Math.round(q.y+(float)Math.sin(q.angle)*probe);
            if(!gap && (solidAt(px,py) || solidAt(Math.round(q.x),Math.round(q.y)))) { kill(i); continue; }
            if(!gap) drawSegment(q,q.x,q.y,nx,ny);
            q.x=nx; q.y=ny;
        }

        int alive=0,last=-1;
        for(int i=0;i<count;i++) if(players[i]!=null && players[i].alive){alive++;last=i;}
        if(alive<=1) {
            if(alive==1 && last>=0) { scores[last]+=count-1; deathOrder.add(last); }
            mode=Mode.ROUND_OVER; roundOverUntil=System.currentTimeMillis()+1700;
        }
    }

    void kill(int i) {
        if(players[i]==null || !players[i].alive) return;
        players[i].alive=false;
        players[i].left=false; players[i].right=false;
        deathOrder.add(i);
        scores[i]+=deathOrder.size()-1;
    }

    // AI chooses among straight, left and right by looking ahead through the bitmap.
    // Higher difficulties inspect farther and react more often; hard also tries to move toward opponents.
    void updateAI(Player q,long now) {
        if(now<q.nextAiMs) return;
        long delay=difficulty==1 ? 260 : difficulty==2 ? 135 : 65;
        q.nextAiMs=now+delay;
        float[] turns={-1f,0f,1f};
        float best=-Float.MAX_VALUE, bestTurn=0;
        for(float t:turns) {
            float a=q.angle + t*(float)Math.toRadians(difficulty==1?38:difficulty==2?48:58);
            float score=spaceScore(q,a);
            if(difficulty>=2) score += openSideScore(q,a)*0.35f;
            if(difficulty==3) score += attackScore(q,a)*0.55f;
            score += rnd.nextFloat()*(difficulty==1?30: difficulty==2?10:3);
            if(score>best){best=score;bestTurn=t;}
        }
        q.left=bestTurn<0; q.right=bestTurn>0;
    }

    float spaceScore(Player q,float a) {
        int max=difficulty==1?100:difficulty==2?155:230;
        int steps=20; float score=0;
        for(int d=12;d<=max;d+=max/steps) {
            int x=Math.round(q.x+(float)Math.cos(a)*d), y=Math.round(q.y+(float)Math.sin(a)*d);
            if(solidAt(x,y)) break;
            score += 1.0f;
        }
        // Look a little to both sides on normal/hard so the AI prefers open corridors.
        if(difficulty>=2) {
            for(int side=-1;side<=1;side+=2) {
                float aa=a+side*(float)Math.toRadians(18);
                for(int d=20;d<=100;d+=20) {
                    if(solidAt(Math.round(q.x+(float)Math.cos(aa)*d),Math.round(q.y+(float)Math.sin(aa)*d))) break;
                    score+=0.18f;
                }
            }
        }
        return score;
    }

    float openSideScore(Player q,float a) {
        float left=a-(float)Math.PI/2f, right=a+(float)Math.PI/2f;
        return spaceScore(q,left)*0.5f + spaceScore(q,right)*0.5f;
    }

    float attackScore(Player q,float a) {
        float best=0;
        int count=totalPlayers();
        for(int i=0;i<count;i++) if(players[i]!=null && players[i]!=q && players[i].alive) {
            float dx=players[i].x-q.x, dy=players[i].y-q.y;
            float dist=(float)Math.sqrt(dx*dx+dy*dy);
            if(dist<1) continue;
            float target=(float)Math.atan2(dy,dx);
            float diff=Math.abs(normalizeAngle(target-a));
            float value=(float)Math.max(0,900-dist*2) * (1f-diff/(float)Math.PI);
            best=Math.max(best,value/40f);
        }
        return best;
    }

    float normalizeAngle(float a) {
        while(a>Math.PI)a-=2*Math.PI;
        while(a<-Math.PI)a+=2*Math.PI;
        return a;
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c); layoutGame(); update();
        c.drawColor(Color.BLACK);
        if(mode==Mode.MENU) { drawMenu(c); invalidate(); return; }
        c.drawBitmap(trail,0,0,p);
        p.setStyle(Paint.Style.FILL); p.setAntiAlias(false);
        int count=totalPlayers();
        for(int i=0;i<count;i++) if(players[i]!=null && players[i].alive) {
            p.setColor(players[i].color); c.drawRect(players[i].x-4,players[i].y-4,players[i].x+5,players[i].y+5,p);
        }
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2); p.setColor(Color.WHITE);
        c.drawRect(gameL,gameT,gameR,gameB,p);
        drawHud(c); drawControls(c);
        if(mode==Mode.COUNTDOWN) {
            int n=(int)Math.ceil((countdownUntil-System.currentTimeMillis())/500.0);
            text.setTextSize(Math.min(width,height)*.18f); text.setColor(Color.WHITE);
            c.drawText(n>0?""+n:"START",width/2f,(gameT+gameB)/2f,text); invalidate();
        } else if(mode==Mode.ROUND_OVER) {
            drawOverlay(c,"KONEC KOLA", "Další kolo...");
            if(System.currentTimeMillis()>=roundOverUntil) finishRound(); else invalidate();
        } else if(mode==Mode.MATCH_OVER) {
            drawMatchOver(c);
        } else invalidate();
    }

    void drawHud(Canvas c) {
        text.setTypeface(Typeface.MONOSPACE);
        text.setTextSize(Math.max(18,Math.min(width,height)*.036f));
        int count=totalPlayers();
        for(int i=0;i<count;i++) {
            text.setColor(players[i].color);
            String s=(i==0 && gameType==GameType.SOLO?"TY  ":"H"+(i+1)+"  ")+scores[i];
            float x=width*(i+1f)/(count+1f);
            float y=gameT-12;
            c.drawText(s,x,y,text);
        }
    }

    void drawControls(Canvas c) {
        // Always visible, outside the clean play field.
        int topBottom=gameT;
        int bottomTop=gameB;
        int count=totalPlayers();
        int humanCount=gameType==GameType.SOLO?1:count;
        for(int i=0;i<humanCount;i++) {
            if(gameType==GameType.SOLO && i>0) continue;
            boolean top = gameType==GameType.SOLO ? false : (count==2 ? i==0 : i<2);
            int half = (count==2?0:(i%2));
            float left=half==0?0:width/2f, right=half==0?width/2f:width;
            drawControlZone(c, top, left, right, top?0:gameB, top?gameT:height, i);
        }
    }

    void drawControlZone(Canvas c, boolean top,float left,float right,float y0,float y1,int playerIndex) {
        p.setStyle(Paint.Style.FILL); p.setColor(Color.argb(18,255,255,255)); p.setAntiAlias(false);
        c.drawRect(left,y0,right,y1,p);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2); p.setColor(Color.argb(155,255,255,255));
        c.drawRect(left+8,y0+8,right-8,y1-8,p);
        float mid=(left+right)/2f;
        float cy=(y0+y1)/2f;
        // The upper player's controls are mirrored: screen-left means right turn.
        String l=top?"VPRAVO":"VLEVO";
        String r=top?"VLEVO":"VPRAVO";
        text.setTextSize(Math.max(18,Math.min(width,height)*.032f));
        text.setColor(players[playerIndex].color);
        c.drawText("H"+(playerIndex+1),mid, y0+25,text);
        text.setColor(Color.WHITE); text.setTextSize(Math.max(16,Math.min(width,height)*.026f));
        c.drawText(l,(left+mid)/2f,cy+8,text); c.drawText(r,(mid+right)/2f,cy+8,text);
        p.setColor(Color.argb(90,255,255,255)); c.drawLine(mid,y0+18,mid,y1-18,p);
    }

    void finishRound() {
        int count=totalPlayers();
        for(int i=0;i<count;i++) if(scores[i]>=targetScore){ mode=Mode.MATCH_OVER; return; }
        startRound();
    }

    void drawOverlay(Canvas c,String title,String sub) {
        p.setStyle(Paint.Style.FILL); p.setColor(Color.argb(205,0,0,0)); c.drawRect(0,0,width,height,p);
        text.setColor(Color.WHITE); text.setTextSize(Math.min(width,height)*.075f); c.drawText(title,width/2f,height*.45f,text);
        text.setTextSize(Math.min(width,height)*.032f); c.drawText(sub,width/2f,height*.55f,text);
    }

    void drawMatchOver(Canvas c) {
        drawOverlay(c,"ZÁPAS SKONČIL","Klepni pro návrat do menu");
        int count=totalPlayers();
        text.setTextSize(Math.min(width,height)*.04f); float y=height*.64f;
        for(int i=0;i<count;i++) { text.setColor(players[i].color); c.drawText((i==0&&gameType==GameType.SOLO?"TY":"H"+(i+1))+"  "+scores[i],width/2f,y,text); y+=Math.min(width,height)*.055f; }
    }

    void drawMenu(Canvas c) {
        p.setStyle(Paint.Style.FILL); p.setColor(Color.BLACK); c.drawRect(0,0,width,height,p);
        text.setColor(Color.WHITE); text.setTypeface(Typeface.MONOSPACE);
        text.setTextSize(Math.min(width,height)*.10f); c.drawText("TRON",width/2f,height*.11f,text);
        text.setTextSize(Math.min(width,height)*.055f); c.drawText("LOCAL",width/2f,height*.17f,text);
        if(menuPage==0) drawMainMenu(c); else if(menuPage==1) drawMultiMenu(c); else drawSoloMenu(c);
    }

    void drawMainMenu(Canvas c) {
        float y=height*.28f;
        drawBigButton(c,y,"HRA PRO VÍCE HRÁČŮ",gameType==GameType.MULTI);
        drawBigButton(c,y+height*.16f,"HRA PRO JEDNOHO",gameType==GameType.SOLO);
        text.setColor(Color.argb(150,255,255,255)); text.setTextSize(Math.min(width,height)*.026f);
        c.drawText("2–4 hráči na jednom telefonu",width/2f,height*.79f,text);
        c.drawText("Dotykové ovládání • bez internetu",width/2f,height*.84f,text);
        drawBigButton(c,height*.90f,"POKRAČOVAT",false);
    }

    void drawMultiMenu(Canvas c) {
        text.setTextSize(Math.min(width,height)*.045f); text.setColor(Color.WHITE); c.drawText("HRA PRO VÍCE HRÁČŮ",width/2f,height*.24f,text);
        drawOption(c,height*.34f,"POČET HRÁČŮ",activePlayers+" hráči");
        drawOption(c,height*.49f,"BODY DO VÍTĚZSTVÍ",targetScore+" bodů");
        drawOption(c,height*.64f,"RYCHLOST",speedName());
        drawBigButton(c,height*.80f,"SPUSTIT HRU",false);
        drawSmallBack(c,height*.91f);
    }

    void drawSoloMenu(Canvas c) {
        text.setTextSize(Math.min(width,height)*.045f); text.setColor(Color.WHITE); c.drawText("HRA PRO JEDNOHO",width/2f,height*.24f,text);
        drawOption(c,height*.34f,"POČET PROTIVNÍKŮ",(activePlayers)+"  (celkem "+(activePlayers+1)+")");
        drawOption(c,height*.47f,"OBTÍŽNOST",difficultyName());
        drawOption(c,height*.60f,"BODY DO VÍTĚZSTVÍ",targetScore+" bodů");
        drawOption(c,height*.73f,"RYCHLOST",speedName());
        drawBigButton(c,height*.86f,"SPUSTIT HRU",false);
        drawSmallBack(c,height*.95f);
    }

    String speedName(){ return speedLevel==1?"POMALÁ":speedLevel==2?"NORMÁLNÍ":"RYCHLÁ"; }
    String difficultyName(){ return difficulty==1?"LEHKÁ":difficulty==2?"STŘEDNÍ":"TĚŽKÁ"; }

    void drawOption(Canvas c,float cy,String label,String value) {
        float w=Math.min(width*.86f,650), h=Math.max(64,Math.min(width,height)*.085f);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2); p.setColor(Color.WHITE); c.drawRect(width/2f-w/2,cy-h/2,width/2f+w/2,cy+h/2,p);
        text.setTextSize(Math.min(width,height)*.023f); text.setColor(Color.argb(170,255,255,255)); c.drawText(label,width/2f,cy-7,text);
        text.setTextSize(Math.min(width,height)*.032f); text.setColor(Color.WHITE); c.drawText(value,width/2f,cy+22,text);
        text.setTextSize(Math.min(width,height)*.026f); text.setColor(Color.argb(170,255,255,255));
        c.drawText("KLEPNI PRO ZMĚNU",width/2f+w/2-95,cy-25,text);
    }

    void drawBigButton(Canvas c,float cy,String label,boolean selected) {
        float w=Math.min(width*.82f,650), h=Math.max(66,Math.min(width,height)*.09f);
        p.setStyle(Paint.Style.FILL); p.setColor(selected?Color.WHITE:Color.BLACK); c.drawRect(width/2f-w/2,cy-h/2,width/2f+w/2,cy+h/2,p);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2); p.setColor(Color.WHITE); c.drawRect(width/2f-w/2,cy-h/2,width/2f+w/2,cy+h/2,p);
        text.setTextSize(Math.min(width,height)*.032f); text.setColor(selected?Color.BLACK:Color.WHITE); c.drawText(label,width/2f,cy+11,text);
    }

    void drawSmallBack(Canvas c,float cy) {
        text.setColor(Color.argb(180,255,255,255)); text.setTextSize(Math.min(width,height)*.028f); c.drawText("ZPĚT",width/2f,cy,text);
    }

    boolean inRect(float x,float y,float top,float bottom){ return y>top && y<bottom; }

    @Override public boolean onTouchEvent(MotionEvent e) {
        float x=e.getX(), y=e.getY();
        int action=e.getActionMasked();

        if(mode==Mode.MENU) {
            if(action!=MotionEvent.ACTION_UP) return true;
            if(menuPage==0) {
                if(inRect(x,y,height*.20f,height*.38f)){ gameType=GameType.MULTI; menuPage=1; invalidate(); return true; }
                if(inRect(x,y,height*.38f,height*.55f)){ gameType=GameType.SOLO; menuPage=2; invalidate(); return true; }
                if(inRect(x,y,height*.84f,height*.97f)) { gameType=GameType.MULTI; menuPage=1; invalidate(); return true; }
            } else if(menuPage==1) {
                if(inRect(x,y,height*.29f,height*.40f)){ activePlayers=activePlayers>=4?2:activePlayers+1; invalidate(); return true; }
                if(inRect(x,y,height*.43f,height*.55f)){ targetScore=targetScore==50?5:targetScore==5?10:targetScore==10?20:50; invalidate(); return true; }
                if(inRect(x,y,height*.58f,height*.70f)){ speedLevel=speedLevel==3?1:speedLevel+1; invalidate(); return true; }
                if(inRect(x,y,height*.74f,height*.88f)){ startMatch(); return true; }
                if(y>height*.88f){ menuPage=0; invalidate(); return true; }
            } else {
                if(inRect(x,y,height*.29f,height*.40f)){ activePlayers=activePlayers>=3?1:activePlayers+1; invalidate(); return true; }
                if(inRect(x,y,height*.42f,height*.53f)){ difficulty=difficulty>=3?1:difficulty+1; invalidate(); return true; }
                if(inRect(x,y,height*.55f,height*.66f)){ targetScore=targetScore==50?5:targetScore==5?10:targetScore==10?20:50; invalidate(); return true; }
                if(inRect(x,y,height*.68f,height*.80f)){ speedLevel=speedLevel==3?1:speedLevel+1; invalidate(); return true; }
                if(inRect(x,y,height*.81f,height*.92f)){ startMatch(); return true; }
                if(y>height*.91f){ menuPage=0; invalidate(); return true; }
            }
            return true;
        }

        if(mode==Mode.MATCH_OVER && action==MotionEvent.ACTION_UP){ mode=Mode.MENU; menuPage=0; invalidate(); return true; }

        if(mode==Mode.PLAYING || mode==Mode.COUNTDOWN) {
            int idx=controlPlayer(x,y);
            if(idx>=0 && players[idx]!=null && !players[idx].ai) {
                boolean leftTurn=controlMeansLeft(x,y,idx);
                if(action==MotionEvent.ACTION_DOWN || action==MotionEvent.ACTION_MOVE) {
                    players[idx].left=leftTurn; players[idx].right=!leftTurn;
                } else if(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL) {
                    players[idx].left=false; players[idx].right=false;
                }
                return true;
            }
            if(action==MotionEvent.ACTION_UP && y>=gameT && y<=gameB) return true;
        }
        return true;
    }

    int controlPlayer(float x,float y) {
        int count=totalPlayers();
        if(gameType==GameType.SOLO) return y>gameB ? 0 : -1;
        if(count==2) {
            if(y<gameT) return 0;
            if(y>gameB) return 1;
            return -1;
        }
        if(y<gameT) return x<width/2f?0:1;
        if(y>gameB) return x<width/2f?2:3;
        return -1;
    }

    boolean controlMeansLeft(float x,float y,int idx) {
        boolean top;
        if(gameType==GameType.SOLO) top=false; else top=(totalPlayers()==2?idx==0:idx<2);
        boolean screenLeft=x<width/2f;
        return top ? !screenLeft : screenLeft;
    }
}
