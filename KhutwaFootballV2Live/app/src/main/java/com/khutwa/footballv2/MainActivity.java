package com.khutwa.footballv2;

import android.app.*;
import android.os.*;
import android.graphics.*;
import android.graphics.Shader;
import android.graphics.LinearGradient;
import android.media.*;
import android.view.*;
import java.util.*;

public class MainActivity extends Activity {
 @Override public void onCreate(Bundle b){
  super.onCreate(b);
  getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,WindowManager.LayoutParams.FLAG_FULLSCREEN);
  setContentView(new MatchView());
 }

 class MatchView extends View {
  Paint p=new Paint(3), line=new Paint(3);
  Typeface bold=Typeface.create("sans-serif",Typeface.BOLD);
  Random rnd=new Random(4);
  ToneGenerator tone=new ToneGenerator(AudioManager.STREAM_MUSIC,80);

  String[] blue={"سالم","خالد","مازن","أحمد","يوسف","طارق"};
  String[] red={"راشد","فيصل","محمد","عبدالله","سعيد","نواف"};
  float[][] bp={{.08f,.50f},{.24f,.25f},{.24f,.75f},{.43f,.50f},{.56f,.27f},{.66f,.64f}};
  float[][] rp={{.92f,.50f},{.76f,.25f},{.76f,.75f},{.57f,.50f},{.44f,.27f},{.34f,.64f}};
  int[] bseq={3,4,5,1,2}, rseq={3,4,5,1,2};

  String[] qs={"ما ناتج 8 × 7 ؟","ما عاصمة سلطنة عُمان؟","ما ناتج 45 ÷ 9 ؟","ما الكوكب الأحمر؟","ما ناتج 12 + 19 ؟","كم ضلعًا للمسدس؟","ما أكبر قارات العالم مساحة؟","ما ناتج 9 × 6 ؟"};
  String[][] op={{"54","56","64","48"},{"صلالة","صحار","مسقط","نزوى"},{"4","5","6","9"},{"الزهرة","المريخ","المشتري","عطارد"},{"29","30","31","32"},{"5","6","7","8"},{"أفريقيا","أوروبا","آسيا","أمريكا الجنوبية"},{"45","54","56","63"}};
  int[] ok={1,2,1,1,2,1,2,1};

  RectF[] ans={new RectF(),new RectF(),new RectF(),new RectF()};
  RectF buzzB=new RectF(),buzzR=new RectF();
  int possession=-1,holder=3,step=0,q=0,bs=0,rs=0;
  long start=System.currentTimeMillis(), bannerTill=0, animStart=0;
  String banner="صافرة البداية — سؤال السرعة!";
  boolean anim=false,kickoff=true;
  int animKind=0,fromTeam,toTeam,fromPlayer,toPlayer,goalTeam;
  float ballX=.5f,ballY=.5f,ballZ=0,fromX=.5f,fromY=.5f,toX=.5f,toY=.5f;
  boolean goal;

  MatchView(){ super(MainActivity.this); setLayerType(View.LAYER_TYPE_SOFTWARE,null); line.setStyle(Paint.Style.STROKE); }

  float L(){return getWidth()*.035f;} float R(){return getWidth()*.965f;}
  float T(){return getHeight()*.35f;} float B(){return getHeight()*.985f;}
  float fx(float x){return L()+(R()-L())*x;} float fy(float y){return T()+(B()-T())*y;}

  @Override protected void onDraw(Canvas c){
   long now=System.currentTimeMillis();
   update(now);
   stadium(c,now); hud(c,now); pitch(c); players(c,now); ball(c); panel(c); overlay(c,now);
   postInvalidateOnAnimation();
  }

  void stadium(Canvas c,long now){
   int w=getWidth(),h=getHeight();
   p.setShader(new LinearGradient(0,0,0,h*.36f,0xff07131f,0xff284356,Shader.TileMode.CLAMP));
   c.drawRect(0,0,w,h*.36f,p); p.setShader(null);
   p.setColor(0xff172634); c.drawRect(0,h*.12f,w,h*.35f,p);
   for(int row=0;row<8;row++){
    for(int i=0;i<85;i++){
     int z=(i+row)%6; p.setColor(z==0?0xffd84747:z==1?0xff4a85d8:z==2?0xffe2d7b5:0xff8898a8);
     float x=(i*37+row*19)%w, y=h*.13f+row*h*.026f+(float)Math.sin(now/150.0+i)*1.6f;
     c.drawCircle(x,y,2.3f+(i%3),p);
    }
   }
   p.setColor(0xff0a2236); c.drawRect(0,h*.33f,w,h*.37f,p);
   p.setColor(Color.WHITE);p.setTypeface(bold);p.setTextAlign(Paint.Align.CENTER);p.setTextSize(h*.022f);
   for(int i=0;i<8;i++) c.drawText("خطوة",(i+.5f)*w/8f,h*.36f,p);
   for(int i=0;i<5;i++){p.setColor(0x88ffffff);c.drawCircle((i+.5f)*w/5f,14,9,p);}
  }

  void hud(Canvas c,long now){
   int w=getWidth(),h=getHeight();
   p.setColor(0xe90a1725); c.drawRoundRect(new RectF(w*.02f,8,w*.98f,h*.112f),22,22,p);
   p.setShader(new LinearGradient(w*.02f,0,w*.34f,0,0xff0e5bb8,0xff0a1725,Shader.TileMode.CLAMP));
   c.drawRoundRect(new RectF(w*.02f,8,w*.34f,h*.112f),22,22,p);p.setShader(null);
   p.setShader(new LinearGradient(w*.66f,0,w*.98f,0,0xff0a1725,0xffb52323,Shader.TileMode.CLAMP));
   c.drawRoundRect(new RectF(w*.66f,8,w*.98f,h*.112f),22,22,p);p.setShader(null);
   p.setTextAlign(Paint.Align.CENTER);p.setTypeface(bold);p.setColor(Color.WHITE);p.setTextSize(h*.031f);
   c.drawText("الفريق الأزرق",w*.17f,h*.049f,p);c.drawText("الفريق الأحمر",w*.83f,h*.049f,p);
   p.setTextSize(h*.055f);c.drawText(bs+"  -  "+rs,w*.5f,h*.064f,p);
   long rem=Math.max(0,8*60*1000-(now-start)); p.setTextSize(h*.019f);p.setColor(0xffcbe4f2);
   c.drawText(String.format(Locale.US,"%02d:%02d",rem/60000,(rem/1000)%60),w*.5f,h*.096f,p);
   for(int i=0;i<6;i++){face(c,w*(.075f+i*.036f),h*.085f,h*.015f,0);face(c,w*(.925f-i*.036f),h*.085f,h*.015f,1);}
  }

  void pitch(Canvas c){
   int w=getWidth(),h=getHeight(); float l=L(),r=R(),t=T(),b=B(),cx=(l+r)/2,cy=(t+b)/2;
   p.setShader(new LinearGradient(0,t,0,b,0xff32994a,0xff176d35,Shader.TileMode.CLAMP));c.drawRect(l,t,r,b,p);p.setShader(null);
   for(int i=0;i<12;i++) if(i%2==0){p.setColor(0x102bff72);c.drawRect(l+(r-l)*i/12,t,l+(r-l)*(i+1)/12,b,p);}
   line.setColor(0xeaffffff);line.setStrokeWidth(Math.max(2,h*.0035f));
   c.drawRect(l+2,t+2,r-2,b-2,line);c.drawLine(cx,t,cx,b,line);c.drawCircle(cx,cy,(b-t)*.12f,line);
   float bw=(r-l)*.13f,bh=(b-t)*.46f;
   c.drawRect(l,cy-bh/2,l+bw,cy+bh/2,line);c.drawRect(r-bw,cy-bh/2,r,cy+bh/2,line);
   c.drawRect(l,cy-bh*.23f,l+bw*.46f,cy+bh*.23f,line);c.drawRect(r-bw*.46f,cy-bh*.23f,r,cy+bh*.23f,line);
   p.setColor(0xffe9f1f4);c.drawRect(l-12,cy-bh*.18f,l,cy+bh*.18f,p);c.drawRect(r,cy-bh*.18f,r+12,cy+bh*.18f,p);
  }

  void players(Canvas c,long now){
   for(int team=0;team<2;team++){
    float[][] a=team==0?bp:rp;
    for(int i=0;i<6;i++){
     float wob=(float)Math.sin(now/210.0+i*1.7)*.006f;
     boolean run=anim&&((team==fromTeam&&i==fromPlayer)||(team==toTeam&&i==toPlayer));
     player(c,fx(a[i][0]),fy(a[i][1]+wob*(run?3:1)),team,i,run,now);
    }
   }
  }

  void player(Canvas c,float x,float y,int team,int idx,boolean run,long now){
   float s=.78f+.35f*((y-T())/(B()-T())),r=getHeight()*.021f*s;
   p.setColor(0x55000000);c.drawOval(new RectF(x-r*1.1f,y+r*1.76f,x+r*1.1f,y+r*2.12f),p);
   float sw=run?(float)Math.sin(now/75.0+idx)*r*.62f:(float)Math.sin(now/330.0+idx)*r*.12f;
   line.setStrokeCap(Paint.Cap.ROUND);line.setStrokeWidth(r*.30f);line.setColor(0xffe9edf1);
   c.drawLine(x-r*.32f,y+r*.85f,x-r*.5f+sw,y+r*1.78f,line);c.drawLine(x+r*.32f,y+r*.85f,x+r*.5f-sw,y+r*1.78f,line);
   line.setColor(team==0?0xff1c63c7:0xffc83232);c.drawLine(x-r*.5f+sw,y+r*1.78f,x-r*.54f+sw,y+r*2.0f,line);c.drawLine(x+r*.5f-sw,y+r*1.78f,x+r*.54f-sw,y+r*2.0f,line);
   Path body=new Path();body.moveTo(x-r*.72f,y-r*.18f);body.lineTo(x+r*.72f,y-r*.18f);body.lineTo(x+r*.52f,y+r*.95f);body.lineTo(x-r*.52f,y+r*.95f);body.close();
   p.setShader(new LinearGradient(x-r,y-r,x+r,y+r,team==0?0xff2d84df:0xffdc4545,team==0?0xff0c3f86:0xff8f1b1b,Shader.TileMode.CLAMP));c.drawPath(body,p);p.setShader(null);
   line.setStrokeWidth(r*.27f);line.setColor(0xffc99271);c.drawLine(x-r*.64f,y-r*.02f,x-r*1.02f-sw*.3f,y+r*.62f,line);c.drawLine(x+r*.64f,y-r*.02f,x+r*1.02f+sw*.3f,y+r*.62f,line);
   p.setColor(0xffd3a07e);c.drawCircle(x,y-r*.93f,r*.61f,p);p.setColor(0xff2d201a);c.drawArc(new RectF(x-r*.63f,y-r*1.56f,x+r*.63f,y-r*.40f),180,180,true,p);
   p.setColor(0xff1a1715);c.drawCircle(x-r*.19f,y-r*.96f,r*.055f,p);c.drawCircle(x+r*.19f,y-r*.96f,r*.055f,p);
   line.setStrokeWidth(Math.max(1,r*.04f));line.setColor(0xff824d38);c.drawArc(new RectF(x-r*.18f,y-r*.82f,x+r*.18f,y-r*.66f),0,180,false,line);
   p.setTypeface(bold);p.setTextAlign(Paint.Align.CENTER);p.setColor(Color.WHITE);p.setTextSize(r*.63f);c.drawText(""+(idx+1),x,y+r*.56f,p);
   p.setTextSize(r*.52f);p.setShadowLayer(3,0,1,Color.BLACK);c.drawText(team==0?blue[idx]:red[idx],x,y-r*1.78f,p);p.clearShadowLayer();
   if(possession==team&&holder==idx&&!anim){line.setStrokeWidth(r*.12f);line.setColor(team==0?0xff63c6ff:0xffff6a62);c.drawCircle(x,y+r*2.0f,r*1.08f,line);}
  }

  void face(Canvas c,float x,float y,float r,int team){
   p.setColor(0xffd2a17e);c.drawCircle(x,y,r,p);p.setColor(0xff2a201c);c.drawArc(new RectF(x-r,y-r,x+r,y+r),180,180,true,p);
   p.setColor(team==0?0xff2c86df:0xffda4444);c.drawCircle(x,y+r*1.15f,r*.48f,p);
  }

  void ball(Canvas c){
   float x=fx(ballX),y=fy(ballY)-ballZ*getHeight()*.12f,r=getHeight()*.0105f;
   p.setColor(0x55000000);c.drawOval(new RectF(x-r*1.15f,fy(ballY)+r*.7f,x+r*1.15f,fy(ballY)+r*1.05f),p);
   p.setColor(Color.WHITE);c.drawCircle(x,y,r,p);p.setColor(0xff20262c);c.drawCircle(x,y,r*.22f,p);
   for(int i=0;i<5;i++){double a=i*Math.PI*2/5;c.drawCircle(x+(float)Math.cos(a)*r*.48f,y+(float)Math.sin(a)*r*.48f,r*.16f,p);}
  }

  void panel(Canvas c){
   if(anim)return;int w=getWidth(),h=getHeight();float top=h*.12f,bot=h*.335f;
   p.setColor(0xec0b1c2d);c.drawRoundRect(new RectF(w*.245f,top,w*.755f,bot),24,24,p);
   line.setColor(0x6688cfff);line.setStrokeWidth(2);c.drawRoundRect(new RectF(w*.245f,top,w*.755f,bot),24,24,line);
   p.setTypeface(bold);p.setTextAlign(Paint.Align.CENTER);p.setColor(Color.WHITE);p.setTextSize(h*.028f);c.drawText(qs[q],w*.5f,top+h*.047f,p);
   p.setTextSize(h*.017f);p.setColor(0xffa7cadf);
   c.drawText(kickoff?"الأسرع يضغط زر فريقه":"الإجابة الصحيحة = تمريرة • الخطأ = قطع الكرة",w*.5f,top+h*.078f,p);
   if(kickoff){
    buzzB.set(w*.27f,top+h*.105f,w*.48f,bot-h*.018f);buzzR.set(w*.52f,top+h*.105f,w*.73f,bot-h*.018f);
    p.setColor(0xff156bcf);c.drawRoundRect(buzzB,18,18,p);p.setColor(0xffc02d2d);c.drawRoundRect(buzzR,18,18,p);
    p.setColor(Color.WHITE);p.setTextSize(h*.026f);c.drawText("الأزرق يجيب",buzzB.centerX(),buzzB.centerY()+h*.009f,p);c.drawText("الأحمر يجيب",buzzR.centerX(),buzzR.centerY()+h*.009f,p);
   }else{
    float x0=w*.27f,x1=w*.73f,y0=top+h*.102f,g=w*.008f,cw=(x1-x0-g)/2,ch=(bot-y0-h*.023f)/2;
    for(int i=0;i<4;i++){int rr=i/2,cc=i%2;float l=x0+cc*(cw+g),tt=y0+rr*(ch+h*.009f);ans[i].set(l,tt,l+cw,tt+ch);p.setColor(0xff173e5e);c.drawRoundRect(ans[i],14,14,p);p.setColor(Color.WHITE);p.setTextSize(h*.022f);c.drawText(op[q][i],ans[i].centerX(),ans[i].centerY()+h*.008f,p);}
   }
   if(possession!=-1){
    float x=w*.075f,y=h*.305f,ww=w*.145f;p.setColor(0x99081724);c.drawRoundRect(new RectF(x,y,x+ww,y+h*.021f),12,12,p);
    p.setColor(possession==0?0xff3bb4ff:0xffff615b);c.drawRoundRect(new RectF(x,y,x+ww*Math.min(1,step/4f),y+h*.021f),12,12,p);
    p.setTextAlign(Paint.Align.LEFT);p.setTextSize(h*.014f);p.setColor(Color.WHITE);c.drawText(step>=4?"فرصة تسديد":"تقدم الهجمة",x,y-h*.006f,p);
   }
  }

  void overlay(Canvas c,long now){
   int w=getWidth(),h=getHeight();
   if(now<bannerTill){p.setColor(0xd40a1722);RectF r=new RectF(w*.32f,h*.39f,w*.68f,h*.46f);c.drawRoundRect(r,28,28,p);p.setTextAlign(Paint.Align.CENTER);p.setTypeface(bold);p.setTextSize(h*.028f);p.setColor(Color.WHITE);c.drawText(banner,w*.5f,h*.435f,p);}
   if(anim&&animKind==4){
    float t=(now-animStart)/1000f;p.setTextAlign(Paint.Align.CENTER);p.setTypeface(bold);p.setTextSize(h*.115f);p.setColor(Color.WHITE);p.setShadowLayer(16,0,5,Color.BLACK);c.drawText("هــدف!",w*.5f,h*.57f,p);p.clearShadowLayer();
    for(int i=0;i<30;i++){float x=(float)((i*89+t*240*(i%2==0?1:-1))%w),y=h*.36f+(float)((i*59+t*170)%(h*.55f));p.setColor(i%3==0?0xffffd34e:goalTeam==0?0xff4db7ff:0xffff5c58);c.drawCircle(x,y,4+i%4,p);}
   }
  }

  void update(long now){
   if(!anim){if(possession!=-1){float[][] a=possession==0?bp:rp;ballX=a[holder][0];ballY=a[holder][1];ballZ=0;}return;}
   float dur=animKind==3?900:animKind==4?2100:720,t=Math.min(1f,(now-animStart)/dur),e=(float)(.5-.5*Math.cos(Math.PI*t));
   if(animKind<4){ballX=fromX+(toX-fromX)*e;ballY=fromY+(toY-fromY)*e;ballZ=(float)Math.sin(Math.PI*t)*(animKind==3?.75f:.18f);}
   if(t<1)return;
   if(animKind==1){holder=toPlayer;step++;anim=false;nextQ();banner=step>=4?"دخلتم منطقة التسديد!":"تمريرة ناجحة!";bannerTill=now+900;}
   else if(animKind==2){possession=toTeam;holder=toPlayer;step=0;anim=false;nextQ();banner="قطع الكرة! هجمة مرتدة";bannerTill=now+1100;}
   else if(animKind==3){
    if(goal){if(goalTeam==0)bs++;else rs++;animKind=4;animStart=now;tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD,650);banner="هــــدف!";bannerTill=now+2000;}
    else{possession=1-goalTeam;holder=0;step=0;anim=false;nextQ();banner="تصــدٍ! الكرة للخصم";bannerTill=now+1100;tone.startTone(ToneGenerator.TONE_PROP_NACK,180);}
   }else if(animKind==4){anim=false;animKind=0;possession=-1;kickoff=true;holder=3;step=0;ballX=.5f;ballY=.5f;nextQ();banner="استئناف من المنتصف";bannerTill=now+1100;tone.startTone(ToneGenerator.TONE_PROP_BEEP,160);}
  }

  void nextQ(){q=(q+1)%qs.length;}

  void pass(){
   int[] seq=possession==0?bseq:rseq;int n=seq[Math.min(step+1,seq.length-1)];float[][] a=possession==0?bp:rp;
   fromX=a[holder][0];fromY=a[holder][1];toX=a[n][0];toY=a[n][1];fromTeam=toTeam=possession;fromPlayer=holder;toPlayer=n;animKind=1;anim=true;animStart=System.currentTimeMillis();tone.startTone(ToneGenerator.TONE_PROP_ACK,80);
  }
  void steal(){
   int other=1-possession;float[][] a=possession==0?bp:rp,b=other==0?bp:rp;int n=3;
   fromX=a[holder][0];fromY=a[holder][1];toX=b[n][0];toY=b[n][1];fromTeam=possession;toTeam=other;fromPlayer=holder;toPlayer=n;animKind=2;anim=true;animStart=System.currentTimeMillis();tone.startTone(ToneGenerator.TONE_PROP_NACK,110);
  }
  void shoot(boolean good){
   float[][] a=possession==0?bp:rp;fromX=a[holder][0];fromY=a[holder][1];toX=possession==0?.99f:.01f;toY=.5f+(rnd.nextFloat()-.5f)*.18f;fromTeam=toTeam=possession;fromPlayer=holder;toPlayer=0;goal=good;goalTeam=possession;animKind=3;anim=true;animStart=System.currentTimeMillis();tone.startTone(ToneGenerator.TONE_PROP_ACK,90);
  }

  @Override public boolean onTouchEvent(MotionEvent e){
   if(e.getAction()!=MotionEvent.ACTION_DOWN||anim)return true;float x=e.getX(),y=e.getY();
   if(kickoff){
    if(buzzB.contains(x,y)){possession=0;kickoff=false;holder=3;step=0;banner="الأزرق كان الأسرع!";bannerTill=System.currentTimeMillis()+850;tone.startTone(ToneGenerator.TONE_PROP_BEEP,120);}
    else if(buzzR.contains(x,y)){possession=1;kickoff=false;holder=3;step=0;banner="الأحمر كان الأسرع!";bannerTill=System.currentTimeMillis()+850;tone.startTone(ToneGenerator.TONE_PROP_BEEP,120);}
    return true;
   }
   for(int i=0;i<4;i++)if(ans[i].contains(x,y)){boolean good=i==ok[q];if(step>=4)shoot(good);else if(good)pass();else steal();return true;}
   return true;
  }
 }
}