package com.unlim8ted.celeste;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import java.util.ArrayList;

/** Native onboarding: usable before WebKit/Gecko or the game has started. */
final class SetupScreen extends FrameLayout {
    private final Activity activity;
    private final ArrayList<Button> actions = new ArrayList<>();
    final TextView status;
    private final ProgressBar progress;
    private static final int INK = 0xfff5f3ff, MUTED = 0xffbcbcd6, ACCENT = 0xffff928d;
    SetupScreen(Activity activity, Runnable atlas, Runnable desktop, java.util.function.Consumer<Button> steam) {
        super(activity); this.activity = activity;
        setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xff14182f,0xff242245,0xff343054}));
        addView(new Mountain(activity), new LayoutParams(-1,-1));
        ScrollView scroll = new ScrollView(activity); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        LinearLayout container = column(); container.setGravity(Gravity.CENTER); container.setPadding(dp(28),dp(24),dp(28),dp(24));
        LinearLayout content = new LinearLayout(activity);
        boolean wide = getResources().getConfiguration().screenWidthDp >= 720;
        content.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams width = new LinearLayout.LayoutParams(wide ? dp(Math.min(1040,getResources().getConfiguration().screenWidthDp-56)) : -1,-2);
        container.addView(content,width);
        LinearLayout hero = column(); hero.setPadding(0,dp(8),dp(wide?40:0),dp(wide?8:24));
        hero.addView(text("UNLIM8TED  /  MOBILE PORT",11,ACCENT,true));
        TextView title = text("CELESTE",wide?46:38,INK,true); title.setLetterSpacing(.1f); margin(hero,title,12);
        margin(hero,text("Your mountain.\nAnywhere.",26,INK,true),8);
        margin(hero,text("Bring your copy of Celeste to your phone.\nA one-time setup, then you're ready to climb.",14,MUTED,false),16);
        margin(hero,text("UNOFFICIAL PORT  ·  " + BuildConfig.VERSION_NAME,10,MUTED,false),24);
        content.addView(hero,new LinearLayout.LayoutParams(wide?0:-1,-2,wide?1:0));
        LinearLayout card = column(); card.setPadding(dp(24),dp(22),dp(24),dp(20));
        card.setBackground(shape(0xef14182e,20,0xff4a496b));
        content.addView(card,new LinearLayout.LayoutParams(wide?dp(400):-1,-2));
        card.addView(text("LET'S GET YOU READY",10,ACCENT,true));
        margin(card,text("Add your game",26,INK,true),6);
        margin(card,text("Choose whichever is easiest. Everything stays on your device.",14,MUTED,false),8);
        Button choose = button("Choose Gameplay0.data",true); choose.setOnClickListener(v->atlas.run()); margin(card,choose,20);
        margin(card,text("SMALLEST TRANSFER  ·  RECOMMENDED",10,ACCENT,true),7);
        margin(card,text("Find it in your desktop game folder:\nContent / Graphics / Atlases",12,MUTED,false),7);
        Button zip = button("Import desktop game ZIP",false); zip.setOnClickListener(v->desktop.run()); margin(card,zip,18);
        margin(card,text("ZIP your Celeste folder. We'll find the atlas inside and prepare the mobile game.",12,MUTED,false),7);
        if (steam != null) { Button account=button("Verify with Steam",false);account.setOnClickListener(v->steam.accept(account));margin(card,account,12); }
        Button saves = button("Bringing your saves?",false); saves.setBackgroundColor(Color.TRANSPARENT); saves.setTextColor(MUTED);
        saves.setOnClickListener(v->new AlertDialog.Builder(activity).setTitle("Continue your climb")
            .setMessage("Finish game setup first, then open Options → Load Save. You can select a .celeste save file or a ZIP of your desktop Saves folder. Importing saves does not unlock the game.")
            .setPositiveButton("Got it",null).show()); margin(card,saves,6);
        progress = new ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal); progress.setIndeterminate(true);
        progress.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));progress.setVisibility(GONE); margin(card,progress,8);
        status = text("Your original game file is required. No store login needed.",12,MUTED,false);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); margin(card,status,8);
        scroll.addView(container);addView(scroll,new LayoutParams(-1,-1));
    }
    void busy(boolean busy) { for(Button button:actions){button.setEnabled(!busy);button.setAlpha(busy?.5f:1f);} progress.setVisibility(busy?VISIBLE:GONE);status.setTextColor(MUTED); }
    void error(String message) { busy(false);status.setTextColor(ACCENT);status.setText(message); }
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout view=new LinearLayout(activity);view.setOrientation(LinearLayout.VERTICAL);return view;}
    private TextView text(String value,int size,int color,boolean bold){TextView view=new TextView(activity);view.setText(value);view.setTextSize(size);view.setTextColor(color);view.setFontFeatureSettings("kern");view.setTypeface(Typeface.create(bold?"sans-serif-medium":"sans-serif",Typeface.NORMAL));view.setLineSpacing(dp(3),1);return view;}
    private void margin(LinearLayout parent,View view,int top){LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.topMargin=dp(top);parent.addView(view,params);}
    private GradientDrawable shape(int color,int radius,int stroke){GradientDrawable drawable=new GradientDrawable();drawable.setColor(color);drawable.setCornerRadius(dp(radius));drawable.setStroke(dp(1),stroke);return drawable;}
    private Button button(String title,boolean primary){Button view=new Button(activity);view.setText(title);view.setAllCaps(false);view.setTextSize(14);view.setTypeface(Typeface.create("sans-serif-medium",0));view.setTextColor(primary?0xff211d35:INK);view.setMinHeight(dp(50));view.setPadding(dp(14),dp(8),dp(14),dp(8));view.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x40ffffff),shape(primary?ACCENT:0xff252a45,12,primary?ACCENT:0xff555675),null));actions.add(view);return view;}
    private static final class Mountain extends View {
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        Mountain(Activity activity){super(activity);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        protected void onDraw(Canvas canvas){float w=getWidth(),h=getHeight();paint.setColor(0x55d6ddff);for(int i=0;i<35;i++)canvas.drawCircle((i*137.31f)%w,(i*73.17f)%(h*.75f),i%3==0?2:1,paint);
            Path mountain=new Path();mountain.moveTo(0,h);mountain.lineTo(w*.22f,h*.40f);mountain.lineTo(w*.32f,h*.62f);mountain.lineTo(w*.47f,h*.22f);mountain.lineTo(w*.75f,h);mountain.close();paint.setColor(0x303c6090);canvas.drawPath(mountain,paint);
            Path ridge=new Path();ridge.moveTo(0,h);ridge.lineTo(w*.22f,h*.4f);ridge.lineTo(w*.17f,h*.74f);ridge.lineTo(w*.47f,h*.22f);ridge.lineTo(w*.38f,h*.77f);ridge.lineTo(w*.75f,h);ridge.close();paint.setColor(0x253597b0);canvas.drawPath(ridge,paint);
        }
    }
}
