package com.pan.tronlocal;

import android.app.Activity;
import android.os.Bundle;
import android.os.Build;
import android.graphics.*;
import android.graphics.drawable.ColorDrawable;
import android.view.*;
import android.content.Context;
import java.util.*;

public class MainActivity extends Activity {
    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setNavigationBarColor(Color.BLACK);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        setContentView(new GameView(this));
    }
}

class GameView extends View {
    enum Mode { MENU, COUNTDOWN, PLAYING, ROUND_OVER, MATCH_OVER }
    static class Player {
        float x,y,angle; int color; boolean alive; boolean left,right; int id;
        Player(int id,int color){this.id=id;this.color=color;alive=true;}
    }
    static final int[] COLORS = { Color.rgb(255,60,60), Color.rgb(70,140,255), Color.rgb(60,220,110), Color.rgb(255,220,60) };
    Paint p = new Paint(); Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    Bitmap trail; Canvas trailCanvas; Mode mode=Mode.MENU;
    Player[] players=new Player[4]; int playerCount=2; int targetScore=10;
    int[] scores=new int[4]; ArrayList<Integer> deathOrder=new ArrayList<>();
    long lastFrame, countdownUntil, roundOverUntil; float speed=210f, turnSpeed=(float)Math.toRadians(180); Random rnd=new Random();
    int width,height,gameL,gameT,gameR,gameB; float dt;
    long[] gapUntilMs = new long[4]; long nextGapAfterMs;

    GameView(Context c){super(c); setBackgroundColor(Color.BLACK); setFocusable(true); text.setTypeface(Typeface.MONOSPACE); text.setTextAlign(Paint.Align.CENTER);}

    void layoutGame(){
        width=getWidth(); height=getHeight();
        int margin = Math.max(18, Math.round(Math.min(width,height)*0.035f));
        int control = Math.max(92, Math.round(Math.min(width,height)*0.16f));
        if(width>=height){ gameL=control; gameR=width-control; gameT=margin; gameB=height-margin; }
        else { gameL=margin; gameR=width-margin; gameT=control; gameB=height-control; }
        if(trail==null || trail.getWidth()!=width || trail.getHeight()!=height){
            trail=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888); trailCanvas=new Canvas(trail); trail.eraseColor(Color.BLACK);
        }
    }

    @Override protected void onSizeChanged(int w,int h,int ow,int oh){ super.onSizeChanged(w,h,ow,oh); trail=null; layoutGame(); }

    void startRound(){
        layoutGame(); trail.eraseColor(Color.BLACK); deathOrder.clear(); nextGapAfterMs=0;
        for(int i=0;i<playerCount;i++){ players[i]=new Player(i,COLORS[i]); }
        for(int i=0;i<playerCount;i++){
            boolean ok=false; for(int tries=0;tries<100 && !ok;tries++){
                float x=gameL+40+rnd.nextFloat()*Math.max(1,gameR-gameL-80);
                float y=gameT+40+rnd.nextFloat()*Math.max(1,gameB-gameT-80);
                ok=true; for(int j=0;j<i;j++){float dx=x-players[j].x,dy=y-players[j].y; if(dx*dx+dy*dy<80*80) ok=false;}
                if(ok){players[i].x=x;players[i].y=y;}
            }
            players[i].angle=rnd.nextFloat()*(float)(Math.PI*2); players[i].alive=true; players[i].left=false;players[i].right=false;
            gapUntilMs[i]=System.currentTimeMillis()+150+rnd.nextInt(1000);
        }
        mode=Mode.COUNTDOWN; countdownUntil=System.currentTimeMillis()+1400; lastFrame=System.nanoTime(); invalidate();
    }

    void startMatch(){ Arrays.fill(scores,0); startRound(); }

    boolean solidAt(int x,int y){ if(x<gameL||x>=gameR||y<gameT||y>=gameB)return true; return trail.getPixel(x,y)!=Color.BLACK; }

    void drawSegment(Player pl,float x0,float y0,float x1,float y1){
        p.setColor(pl.color); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(4f); p.setStrokeCap(Paint.Cap.SQUARE); p.setAntiAlias(false); trailCanvas.drawLine(x0,y0,x1,y1,p);
    }

    void update(){
        if(mode==Mode.COUNTDOWN){ if(System.currentTimeMillis()>=countdownUntil) mode=Mode.PLAYING; return; }
        if(mode!=Mode.PLAYING) return;
        long now=System.nanoTime(); dt=Math.min(.033f,(now-lastFrame)/1_000_000_000f); lastFrame=now; 
        for(int i=0;i<playerCount;i++) if(players[i].alive){
            Player q=players[i];
            if(q.left) q.angle-=turnSpeed*dt; if(q.right) q.angle+=turnSpeed*dt;
            float nx=q.x+(float)Math.cos(q.angle)*speed*dt, ny=q.y+(float)Math.sin(q.angle)*speed*dt;
            long nowMs=System.currentTimeMillis(); boolean gap = nowMs < gapUntilMs[i];
            // Collision is sampled ahead of the drawing point, avoiding self-hit on the immediately written pixel.
            float probe = 7f;
            int px=Math.round(q.x+(float)Math.cos(q.angle)*probe), py=Math.round(q.y+(float)Math.sin(q.angle)*probe);
            if(!gap && solidAt(px,py)){ kill(i); continue; }
            if(!gap){ drawSegment(q,q.x,q.y,nx,ny); }
            q.x=nx;q.y=ny;
            if(!gap) gapUntilMs[i]=nowMs+1800+rnd.nextInt(1400);
        }
        int alive=0,last=-1; for(int i=0;i<playerCount;i++)if(players[i].alive){alive++;last=i;}
        if(alive<=1){
            if(alive==1){ scores[last] += playerCount-1; deathOrder.add(last); }
            mode=Mode.ROUND_OVER; roundOverUntil=System.currentTimeMillis()+1800;
        }
    }

    void kill(int i){ if(!players[i].alive)return; players[i].alive=false; deathOrder.add(i); scores[i]+=deathOrder.size()-1; }

    void drawHud(Canvas c){
        text.setTypeface(Typeface.MONOSPACE); text.setTextSize(Math.max(16,Math.min(width,height)*.037f));
        for(int i=0;i<playerCount;i++){
            text.setColor(players[i].color); String s="P"+(i+1)+"  "+scores[i];
            float x=(i%2==0)?gameL/2f:width-gameL/2f; if(width<height){x=(i%2==0)?width*.27f:width*.73f;}
            float y=(i<2)?25:height-18; if(width<height){y=(i<2)?25:height-18;}
            c.drawText(s,x,y,text);
        }
    }

    void drawControls(Canvas c){
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1); p.setAntiAlias(false); p.setColor(Color.argb(55,255,255,255));
        if(width>=height){
            c.drawLine(gameL/2f,0,gameL/2f,height,p); c.drawLine(width-gameL/2f,0,width-gameL/2f,height,p);
            text.setTextSize(13); text.setColor(Color.argb(80,255,255,255)); c.drawText("◀",gameL*.25f,height/2f,text); c.drawText("▶",gameL*.75f,height/2f,text);
            c.drawText("◀",width-gameL*.75f,height/2f,text); c.drawText("▶",width-gameL*.25f,height/2f,text);
        } else {
            c.drawLine(0,controlMid(),width,controlMid(),p); c.drawLine(width/2f,0,width/2f,controlMid(),p); c.drawLine(width/2f,controlMid(),width,height,p);
        }
    }
    float controlMid(){ return gameT/2f; }

    @Override protected void onDraw(Canvas c){ super.onDraw(c); layoutGame(); update();
        c.drawColor(Color.BLACK);
        if(mode==Mode.MENU){ drawMenu(c); invalidate(); return; }
        c.drawBitmap(trail,0,0,p);
        // Heads
        p.setAntiAlias(false); p.setStyle(Paint.Style.FILL);
        for(int i=0;i<playerCount;i++) if(players[i]!=null && players[i].alive){ p.setColor(players[i].color); c.drawRect(players[i].x-3,players[i].y-3,players[i].x+4,players[i].y+4,p); }
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2); p.setColor(Color.WHITE); c.drawRect(gameL,gameT,gameR,gameB,p);
        drawHud(c); drawControls(c);
        if(mode==Mode.COUNTDOWN){int n=(int)Math.ceil((countdownUntil-System.currentTimeMillis())/500.0); text.setTextSize(Math.min(width,height)*.17f); text.setColor(Color.WHITE); c.drawText(n>0?""+n:"GO!",width/2f,height/2f,text);invalidate();}
        else if(mode==Mode.ROUND_OVER){ drawOverlay(c,"KO ROUND","Next round"); if(System.currentTimeMillis()>=roundOverUntil) finishRound(); else invalidate(); }
        else if(mode==Mode.MATCH_OVER){ drawOverlay(c,"MATCH WIN","Tap to return"); }
        else invalidate();
    }

    void finishRound(){ int winner=-1,best=-1; for(int i=0;i<playerCount;i++)if(scores[i]>=targetScore){winner=i;best=scores[i];}
        if(winner>=0){mode=Mode.MATCH_OVER;} else startRound(); }

    void drawOverlay(Canvas c,String title,String sub){ p.setColor(Color.argb(185,0,0,0));p.setStyle(Paint.Style.FILL);c.drawRect(0,0,width,height,p); text.setColor(Color.WHITE); text.setTextSize(Math.min(width,height)*.065f); c.drawText(title,width/2f,height*.43f,text); text.setTextSize(18); c.drawText(sub,width/2f,height*.56f,text); }

    void drawMenu(Canvas c){
        text.setColor(Color.WHITE); text.setTextSize(Math.min(width,height)*.09f); c.drawText("TRON LOCAL",width/2f,height*.18f,text);
        text.setTextSize(16); c.drawText("PLAYERS",width/2f,height*.31f,text);
        drawButton(c,width/2f,height*.39f-30,playerCount+" PLAYERS");
        text.setTextSize(16); c.drawText("MATCH TO",width/2f,height*.52f,text);
        drawButton(c,width/2f,height*.60f-30,targetScore+" POINTS");
        drawButton(c,width/2f,height*.79f,"START");
        text.setColor(Color.argb(140,255,255,255)); text.setTextSize(12); c.drawText("2–4 players • one phone • hold left/right to turn",width/2f,height*.92f,text);
    }
    void drawButton(Canvas c,float cx,float top,String s){ float w=Math.min(width*.65f,320),h=54; p.setAntiAlias(false);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2);p.setColor(Color.WHITE);c.drawRect(cx-w/2,top,cx+w/2,top+h,p);text.setColor(Color.WHITE);text.setTextSize(17);c.drawText(s,cx,top+34,text); }

    @Override public boolean onTouchEvent(android.view.MotionEvent e){
        float x=e.getX(),y=e.getY();
        if(mode==Mode.MENU && e.getAction()==MotionEvent.ACTION_UP){
            if(y>height*.33f && y<height*.46f){playerCount=playerCount==4?2:playerCount+1;invalidate();return true;}
            if(y>height*.54f && y<height*.68f){targetScore=targetScore==50?10:targetScore==10?20:50;invalidate();return true;}
            if(y>height*.75f && y<height*.87f){startMatch();return true;}
        } else if(mode==Mode.MATCH_OVER && e.getAction()==MotionEvent.ACTION_UP){mode=Mode.MENU;invalidate();return true;}
        if(mode==Mode.PLAYING || mode==Mode.COUNTDOWN){ int idx=zonePlayer(x,y); if(idx>=0){ boolean left=zoneLeft(x,y); if(e.getAction()==MotionEvent.ACTION_DOWN || e.getAction()==MotionEvent.ACTION_MOVE){players[idx].left=left;players[idx].right=!left;} else if(e.getAction()==MotionEvent.ACTION_UP || e.getAction()==MotionEvent.ACTION_CANCEL){players[idx].left=false;players[idx].right=false;} return true; } }
        return true;
    }
    int zonePlayer(float x,float y){
        if(width>=height){
            if(x<gameL){ return playerCount>=4 ? (y<height/2f?0:2) : 0; }
            if(x>gameR){ return playerCount>=4 ? (y<height/2f?1:3) : 1; }
            return -1;
        }
        if(y<gameT){return x<width/2f?0:1;}
        if(y>gameB){return x<width/2f?2:3;}
        return -1;
    }
    boolean zoneLeft(float x,float y){
        if(width>=height){ float center=(x<gameL)?gameL/2f:width-gameL/2f; return x<center; }
        return x<width/2f;
    }
}
