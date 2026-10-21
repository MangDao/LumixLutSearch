package io.github.lumixlut.edit;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import io.github.lumixlut.search.R;
import io.github.lumixlut.edit.curve.ToneCurve;
import io.github.lumixlut.edit.curve.ToneCurveSet;
import io.github.lumixlut.edit.gl.filter.Lut3DFilter;
import io.github.lumixlut.edit.lut.CubeParser;
import io.github.lumixlut.edit.lut.LutData;
import io.github.lumixlut.edit.lut.LutThumb;
import io.github.lumixlut.edit.ui.CenterSeekBar;
import io.github.lumixlut.edit.ui.PreviewView;
import io.github.lumixlut.edit.ui.ToneCurveView;

/**
 * 照片编辑页 · 第 1 步：只打通「选图 → 套 LUT → 拖浓度」这条竖线。
 * 后面几个 tab（调整 / 裁剪 / 相框 / 导出）按同一骨架往上长。
 */
public class EditActivity extends Activity {

    private static final int REQ_PICK_PHOTO = 101;
    private static final int REQ_PICK_LUT = 102;
    private static final int REQ_PICK_LUT2 = 103;

    /** 外部直接喂素材用，见 applyIncoming */
    public static final String EXTRA_IMAGE = "image_path";
    public static final String EXTRA_LUT = "lut_path";
    public static final String EXTRA_INTENSITY = "intensity";
    /** auto | baked（素材已烘焙，转 V-Log）| vlog（素材已是 V-Log，直通） */
    public static final String EXTRA_SOURCE_MODE = "source_mode";

    // 配色统一走 colors.xml（和 app 其他页面同源，night 变体也能跟着变）。
    // 原来是写死的深色，一进来黑压压一片，和外面完全两个世界。
    private int BG;
    private int PANEL;
    private int ACCENT;
    private int TEXT;
    private int TEXT_DIM;
    private int TEXT_WEAK;
    private int CHIP_BG;
    private int ON_ACCENT;
    private int LINE;
    private int SCRIM;

    /**
     * 编辑器自己的深色调色板，照 LUMIX Lab 来。
     * 不走 colors.xml 是故意的：主页浅色、编辑页深色，两套互不干扰。
     * 强调色用官方那种黄 —— 深底上青绿会发灰。
     */
    /** 配色跟 App 其他页面同源（colors.xml），编辑页不另开一套 */
    /**
     * 编辑页固定用官方那套深色，不跟主站的浅色走。
     *
     * 官方 base_background=#000000、base_content/gray_l10=#1a1a1a、
     * 正文 #e5ffffff、次级 #99e4e4e4 —— 编辑这种要在暗处评判颜色的活儿，
     * 外面那套浅色界面会把感知带跑。强调色留主站的青，免得两个世界。
     */
    private void initPalette() {
        BG = EDT_BG;
        PANEL = EDT_PANEL;
        ACCENT = getColor(io.github.lumixlut.search.R.color.accent);
        TEXT = EDT_TEXT;
        TEXT_DIM = EDT_TEXT_SUB;
        TEXT_WEAK = EDT_TEXT_WEAK;
        CHIP_BG = 0xFF2A2A2A;
        ON_ACCENT = 0xFFFFFFFF;
        LINE = EDT_LINE;
        SCRIM = 0xCC000000;
    }

    /** 预览区 letterbox 跟着主题走，别在浅色界面上开一块黑 */
    private void applyBackdrop() {
        if (preview != null) {
            preview.setBackdrop(BG);
        }
    }

    private PreviewView preview;
    private TextView lutLabel;
    private TextView sourceLabel;
    private CenterSeekBar intensityBar;
    private CenterSeekBar intensityBar2;
    private boolean intensity2Bound;
    private Bitmap currentBitmap;
    /** 当前照片缩成 216×216 的像素，给 LUT 缩略图当底 */
    private int[] referencePixels;
    private LutData currentLut;
    /** 第二条 LUT，和第一条串联（官方叫 LUT 2） */
    private LutData currentLut2;
    private TextView lutLabel2;
    /** 当前往哪个槽里装 LUT：1 或 2 */
    private int activeLutSlot = 1;
    private SourceProbe source;
    /** 当前素材的原始 Uri，导出时用它重新解全分辨率 */
    private Uri currentUri;
    /** 一次编辑的全部参数，滑杆改它，渲染线程读它的快照 */
    private final EditState state = new EditState();
    /** 四条色调曲线：主 / R / G / B */
    private final ToneCurveSet curves = new ToneCurveSet();
    private ToneCurveView curveView;
    private View curveOverlay;
    private TextView[] channelButtons;
    private int activeCurveRow = ToneCurveSet.ROW_MASTER;

    private LinearLayout cropRow;
    private LinearLayout frameRow;
    private LinearLayout libraryRow;

    // ---- 视频 ----
    private static final int REQ_VIDEO = 4003;
    private java.io.File currentLutFile;
    private java.io.File videoFile;
    private long videoDurationMs;
    private int videoWidth;
    private int videoHeight;
    private float videoSpeed = 2f;
    private TextView videoLabel;
    private LinearLayout speedRow;
    private LinearLayout curveRow;
    private TextView exportVideoBtn;
    private TextView previewVideoEntry;
    private FrameLayout videoOverlay;
    /** 非 null 时走曲线变速，固定档位让位 */
    private io.github.lumixlut.edit.video.CurveSpeedPlan curvePlan;
    private int curvePresetIndex = -1;
    private int cropIndex;
    private int frameIndex;
    private float cropAspect;
    private int frameType;

    /** -1 = 自动（按识别结果），0 = 强制直通，1 = 强制转 V-Log */
    private int overrideMode = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        initPalette();
        setContentView(buildUi());
        applyIncoming(getIntent());
        // 允许外部直接指定浓度（0~1），方便自动验收
        float initial = getIntent() == null ? -1f
                : getIntent().getFloatExtra(EXTRA_INTENSITY, -1f);
        if (initial >= 0f) {
            intensityBar.setProgressSilently(Math.round(Math.min(1f, initial) * 100f));
        }
        String modeExtra = getIntent() == null ? null
                : getIntent().getStringExtra(EXTRA_SOURCE_MODE);
        overrideMode = parseOverride(modeExtra);
        applySourceMode();
        // HyperOS 会在窗口层画一条滚动条白线，横在预览区右边，关掉
        getWindow().getDecorView().setVerticalScrollBarEnabled(false);
        getWindow().getDecorView().setHorizontalScrollBarEnabled(false);
        disableScrollBars(findViewById(android.R.id.content));
    }

    /** 把整棵树上的滚动条全关掉，HyperOS 上那条白竖线就是从这来的 */
    private static void disableScrollBars(View view) {
        if (view == null) {
            return;
        }
        view.setVerticalScrollBarEnabled(false);
        view.setHorizontalScrollBarEnabled(false);
        view.setScrollContainer(false);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                disableScrollBars(group.getChildAt(i));
            }
        }
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        root.addView(buildTopBar());

        // 预览
        preview = new PreviewView(this);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        preview.setLayoutParams(previewParams);
        root.addView(preview);

        // 5 个 tab 各自的 sheet，全部叠在同一个容器里按需显示
        sheetHost = new FrameLayout(this);
        sheetHost.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(SHEET_HEIGHT)));
        // 顺序对齐官方底栏：裁剪 / LUT / 调整 / 相框
        sheets = new View[]{
                buildCropSheet(),
                buildLutSheet(),
                buildAdjustSheet(),
                buildFrameSheet()
        };
        for (View sheet : sheets) {
            sheetHost.addView(sheet, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        // 把手放在面板上方：拖它就能把面板拉高/压低，
        // 预览区是 weight=1，会自动把剩下的空间吃掉，图跟着缩放
        sheetHandle = buildSheetHandle();
        root.addView(sheetHandle);
        root.addView(sheetHost);

        root.addView(buildTabBar());

        FrameLayout shell = new FrameLayout(this);
        shell.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        shell.addView(buildCurveOverlay(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        selectCurveRow(ToneCurveSet.ROW_MASTER);
        rebuildCropChips();
        rebuildFrameChips();
        setSheetExpanded(false);
        updateTabColors();
        refreshLibrary();
        applyBackdrop();

        // 支持直接传视频路径进来（联调用，也为以后「从别处跳转来编辑」留口子）
        String videoPath = getIntent() == null ? null
                : getIntent().getStringExtra("video_path");
        if (videoPath != null && !videoPath.isEmpty()) {
            prepareVideo(Uri.fromFile(new java.io.File(videoPath)));
        }

        // 冷启动时问一句要不要接着上次改
        if (shell != null) {
            shell.post(this::checkDraftOnLaunch);
        }
        return shell;
    }

    // ---------- 底部工具条：官方那 5 个（视频模式多一个「视频」） ----------

    private static final int TAB_CROP = 0;
    private static final int TAB_LUT = 1;
    private static final int TAB_ADJUST = 2;
    private static final int TAB_FRAME = 3;
    private static final int TAB_SHARE = 4;
    /** 官方底栏：裁剪旋转 / LUT / 调整 / 画框 / 共享 */
    private static final int[] TAB_ICONS = {
            R.drawable.edt_tab_crop,
            R.drawable.edt_tab_lut,
            R.drawable.edt_tab_adjust,
            R.drawable.edt_tab_frame,
            R.drawable.edt_tab_share
    };
    /** 官方选中态是另一套图，画框那个没有选中版，就用同一张 */
    private static final int[] TAB_ICONS_ON = {
            R.drawable.edt_tab_crop_on,
            R.drawable.edt_tab_lut_on,
            R.drawable.edt_tab_adjust_on,
            R.drawable.edt_tab_frame,
            R.drawable.edt_tab_share_on
    };

    // ---------- 官方配色（base_background / base_content / dark_theme_text_*） ----------
    private static final int EDT_BG = 0xFF000000;
    private static final int EDT_PANEL = 0xFF1A1A1A;
    private static final int EDT_TEXT = 0xE5FFFFFF;
    private static final int EDT_TEXT_SUB = 0x99E4E4E4;
    private static final int EDT_TEXT_WEAK = 0x66FFFFFF;
    private static final int EDT_LINE = 0xFF808080;
    private static final int EDT_TRACK = 0xFF4D4D4D;
    private static final int EDT_WHITE = 0xFFFFFFFF;

    /** 官方面板高度：40dp 顶栏 + 192dp 身子 */
    private static final int SHEET_HEIGHT = 232;
    private static final int ADJUST_BODY_HEIGHT = 192;
    private static final int RAIL_WIDTH = 48;

    // ---------- 调整面板的三个模式 ----------
    private static final int PANEL_ADJUST = 0;
    private static final int PANEL_HSL = 1;
    private static final int PANEL_SPLIT = 2;
    // 左侧轨道三格：亮度 / 色彩 / 工具
    private static final int RAIL_LIGHT = 0;
    private static final int RAIL_COLOR = 1;
    private static final int RAIL_TOOL = 2;

    /** HSL 八个色板的颜色，和 HslFilter 里的色带中心一一对应 */
    private static final int[] HSL_RGB = {
            0xFFE5484D, 0xFFF07B26, 0xFFE8C63A, 0xFF4CAF50,
            0xFF35B7E5, 0xFF3D5AFE, 0xFF8E5BD9, 0xFFD65BB8
    };
    private static final String[] HSL_NAMES = {
            "红", "橙", "黄", "绿", "天蓝", "蓝", "紫", "兰"
    };
    private static final String[] SPLIT_NAMES = {"阴影", "中间调", "突出显示"};

    private View[] sheets;
    private ImageView[] tabButtons;
    private FrameLayout sheetHost;
    /** 顶栏中间那个标题：官方用来显示 JPEG / MP4 这种格式 */
    private TextView topBarTitle;

    /** 调整面板：左边轨道 + 右边滑杆列表，和官方一样 */
    private LinearLayout adjustList;
    private View[] railMarkers;
    private ImageView[] railIcons;
    private TextView adjustTitle;
    private View[] panelButtons;
    private TextView panelDoneButton;
    private int railTab = RAIL_LIGHT;
    private int adjustPanel = PANEL_ADJUST;
    private int hslColor;
    private int splitRange;
    /** 快照恢复时要回填的滑杆 */
    private final java.util.List<Runnable> adjustSynced = new java.util.ArrayList<>();

    /**
     * 面板顶部的拖拽把手。
     *
     * 拖动改的是 sheetHost 的高度，预览区靠 weight=1 自动跟着变 ——
     * 不需要手动碰 PreviewView，SurfaceView 尺寸一变 GL 那边会重新 fit。
     */
    private View buildSheetHandle() {
        LinearLayout holder = new LinearLayout(this);
        holder.setOrientation(LinearLayout.VERTICAL);
        holder.setGravity(Gravity.CENTER);
        holder.setBackgroundColor(PANEL);
        holder.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(20)));
        holder.setContentDescription("上下拖动调整面板高度");

        View grip = new View(this);
        android.graphics.drawable.GradientDrawable gripBg =
                new android.graphics.drawable.GradientDrawable();
        gripBg.setCornerRadius(dp(2));
        gripBg.setColor(TEXT_WEAK);
        grip.setBackground(gripBg);
        grip.setLayoutParams(new LinearLayout.LayoutParams(dp(40), dp(4)));
        holder.addView(grip);

        holder.setOnTouchListener(new View.OnTouchListener() {
            private float startRawY;
            private int startHeight;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startRawY = event.getRawY();
                        startHeight = sheetHost.getLayoutParams().height;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        // 往上滑 delta 为正 => 面板变高
                        int delta = (int) (startRawY - event.getRawY());
                        int target = Math.max(dp(150),
                                Math.min(maxSheetHeight(), startHeight + delta));
                        ViewGroup.LayoutParams lp = sheetHost.getLayoutParams();
                        if (lp.height != target) {
                            lp.height = target;
                            sheetHost.setLayoutParams(lp);
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        return true;
                    default:
                        return false;
                }
            }
        });
        return holder;
    }

    /** 面板最高能拉到多少：给顶栏、把手、tab 栏和预览区都留出位置 */
    private int maxSheetHeight() {
        View content = findViewById(android.R.id.content);
        int total = content == null ? 0 : content.getHeight();
        if (total <= 0) {
            total = getResources().getDisplayMetrics().heightPixels;
        }
        int reserved = dp(48) + dp(20) + dp(54) + dp(14) + dp(200);
        return Math.max(dp(150), total - reserved);
    }
    private int activeTab = -1;
    private boolean sheetExpanded;
    private View sheetHandle;

    private ScrollView wrapSheet(LinearLayout content) {
        ScrollView scroller = new ScrollView(this);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setFillViewport(false);
        scroller.setBackgroundColor(PANEL);
        scroller.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroller;
    }

    private LinearLayout newSheet() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(10), dp(16), dp(14));
        return content;
    }

    private View buildTabBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(EDT_PANEL);
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        tabButtons = new ImageView[TAB_ICONS.length];
        for (int i = 0; i < TAB_ICONS.length; i++) {
            final int index = i;
            ImageView icon = new ImageView(this);
            icon.setImageResource(TAB_ICONS[i]);
            icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            // 官方：weight=1、高 32dp、左右各 4dp padding
            icon.setPadding(dp(4), 0, dp(4), 0);
            icon.setLayoutParams(new LinearLayout.LayoutParams(0, dp(32), 1f));
            icon.setOnClickListener(v -> onTabIconClick(index));
            bar.addView(icon);
            tabButtons[i] = icon;
        }
        return bar;
    }

    // ---------- 顶栏：返回 / 信息 / 标题 / ⋯ / 设置（官方 42dp） ----------

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(EDT_BG);
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));

        ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.ic_back);
        back.setColorFilter(EDT_TEXT);
        back.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        back.setPadding(dp(11), dp(11), dp(11), dp(11));
        back.setLayoutParams(new LinearLayout.LayoutParams(dp(42),
                ViewGroup.LayoutParams.MATCH_PARENT));
        back.setOnClickListener(v -> onBackPressed());
        bar.addView(back);

        TextView info = glyphButton("\u24D8", 18);
        info.setOnClickListener(v -> showSourceInfo());
        bar.addView(info);

        topBarTitle = new TextView(this);
        topBarTitle.setText(R.string.edit_title);
        topBarTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        topBarTitle.setTextColor(EDT_TEXT);
        topBarTitle.setGravity(Gravity.CENTER);
        topBarTitle.setMaxLines(1);
        topBarTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        topBarTitle.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(topBarTitle);

        TextView more = glyphButton("\u22EF", 22);
        more.setOnClickListener(v -> showMoreMenu(more));
        bar.addView(more);

        TextView settings = glyphButton("\u2699", 18);
        settings.setOnClickListener(v -> togglePreviewBackdrop());
        bar.addView(settings);

        return bar;
    }

    /** 顶栏那几个纯字符按钮，省得为 ⓘ/⋯/⚙ 各画一套图 */
    private TextView glyphButton(String glyph, int sizeSp) {
        TextView view = new TextView(this);
        view.setText(glyph);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        view.setTextColor(EDT_TEXT);
        view.setGravity(Gravity.CENTER);
        view.setLayoutParams(new LinearLayout.LayoutParams(dp(40),
                ViewGroup.LayoutParams.MATCH_PARENT));
        return view;
    }

    private void showMoreMenu(View anchor) {
        android.widget.PopupMenu menu = new android.widget.PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "导入照片");
        menu.getMenu().add(0, 2, 1, "导入视频");
        menu.getMenu().add(0, 3, 2, "从我的 LUT 里选");
        menu.getMenu().add(0, 4, 3, "重置编辑");
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    pickPhoto();
                    return true;
                case 2:
                    pickVideo();
                    return true;
                case 3:
                    selectTab(TAB_LUT);
                    return true;
                case 4:
                    commit();
                    state.resetAdjustments();
                    syncAdjustSliders();
                    applyAdjustments();
                    applyCurves();
                    Toast.makeText(this, "已重置", Toast.LENGTH_SHORT).show();
                    return true;
                default:
                    return false;
            }
        });
        menu.show();
    }

    /** 官方设置里那项「预览背景色：黑色 / 浅色」 */
    private void togglePreviewBackdrop() {
        lightBackdrop = !lightBackdrop;
        if (lightBackdrop) {
            preview.setBackdrop(0xFFFFFFFF);
        } else {
            preview.setBackdrop(EDT_BG);
        }
        Toast.makeText(this, lightBackdrop ? "预览背景：浅色" : "预览背景：黑色",
                Toast.LENGTH_SHORT).show();
    }

    private boolean lightBackdrop;

    private void showSourceInfo() {
        StringBuilder sb = new StringBuilder();
        if (sourceLabel != null && sourceLabel.getText() != null) {
            sb.append(sourceLabel.getText());
        }
        if (lutLabel != null && lutLabel.getText() != null && lutLabel.getText().length() > 0) {
            sb.append("\n").append(lutLabel.getText());
        }
        if (sb.length() == 0) {
            sb.append("还没有载入素材");
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("素材信息")
                .setMessage(sb.toString())
                .setPositiveButton("知道了", null)
                .show();
    }

    // ---------- 调整面板：官方那套「左轨道 + 右滑杆」 ----------

    private View buildAdjustSheet() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(EDT_PANEL);
        panel.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(SHEET_HEIGHT)));

        panel.addView(buildAdjustTopBar());
        panel.addView(buildAdjustBody());
        selectRail(RAIL_LIGHT);
        return panel;
    }

    /** 40dp 顶栏：撤销 / 重做 —— 标题 —— 调整 / HSL / 色调分离 */
    private View buildAdjustTopBar() {
        RelativeLayout bar = new RelativeLayout(this);
        bar.setBackgroundColor(EDT_PANEL);
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.HORIZONTAL);
        left.setGravity(Gravity.CENTER_VERTICAL);
        RelativeLayout.LayoutParams leftLp = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        leftLp.addRule(RelativeLayout.ALIGN_PARENT_START);

        undoButton = new ImageView(this);
        undoButton.setImageResource(R.drawable.edt_undo_off);
        undoButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        undoButton.setPadding(dp(12), 0, 0, 0);
        undoButton.setLayoutParams(new LinearLayout.LayoutParams(dp(36), dp(24)));
        undoButton.setOnClickListener(v -> doUndo());
        left.addView(undoButton);

        redoButton = new ImageView(this);
        redoButton.setImageResource(R.drawable.edt_redo_off);
        redoButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        redoButton.setPadding(0, 0, dp(12), 0);
        LinearLayout.LayoutParams redoLp = new LinearLayout.LayoutParams(dp(36), dp(24));
        redoLp.leftMargin = dp(24);
        redoButton.setLayoutParams(redoLp);
        redoButton.setOnClickListener(v -> doRedo());
        left.addView(redoButton);
        bar.addView(left, leftLp);

        adjustTitle = new TextView(this);
        adjustTitle.setText("调整");
        adjustTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        adjustTitle.setTypeface(Typeface.DEFAULT_BOLD);
        adjustTitle.setTextColor(EDT_TEXT);
        RelativeLayout.LayoutParams titleLp = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.addRule(RelativeLayout.CENTER_IN_PARENT);
        bar.addView(adjustTitle, titleLp);

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.HORIZONTAL);
        right.setGravity(Gravity.CENTER_VERTICAL);
        RelativeLayout.LayoutParams rightLp = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(32));
        rightLp.addRule(RelativeLayout.ALIGN_PARENT_END);
        rightLp.addRule(RelativeLayout.CENTER_VERTICAL);
        rightLp.rightMargin = dp(5);

        panelButtons = new View[3];
        int[] panelIcons = {R.drawable.edt_tab_adjust_on, R.drawable.edt_hsl,
                R.drawable.edt_split_tone};
        for (int i = 0; i < 3; i++) {
            final int mode = i;
            ImageView iv = new ImageView(this);
            iv.setImageResource(panelIcons[i]);
            iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            iv.setPadding(dp(3), 0, dp(3), 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(32), dp(32));
            lp.leftMargin = dp(3);
            iv.setLayoutParams(lp);
            iv.setOnClickListener(v -> showAdjustPanel(mode));
            right.addView(iv);
            panelButtons[i] = iv;
        }
        bar.addView(right, rightLp);

        panelDoneButton = new TextView(this);
        panelDoneButton.setText("完成");
        panelDoneButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        panelDoneButton.setTextColor(EDT_TEXT);
        panelDoneButton.setGravity(Gravity.CENTER);
        panelDoneButton.setVisibility(View.GONE);
        RelativeLayout.LayoutParams doneLp = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(32));
        doneLp.addRule(RelativeLayout.ALIGN_PARENT_END);
        doneLp.addRule(RelativeLayout.CENTER_VERTICAL);
        doneLp.rightMargin = dp(12);
        panelDoneButton.setOnClickListener(v -> showAdjustPanel(PANEL_ADJUST));
        bar.addView(panelDoneButton, doneLp);

        return bar;
    }

    /** 左边 48dp 轨道 + 右边滑杆列表 */
    private View buildAdjustBody() {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ADJUST_BODY_HEIGHT)));

        LinearLayout rail = new LinearLayout(this);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setLayoutParams(new LinearLayout.LayoutParams(dp(RAIL_WIDTH),
                ViewGroup.LayoutParams.MATCH_PARENT));
        railMarkers = new View[3];
        railIcons = new ImageView[3];
        int[] railIconsRes = {R.drawable.edt_rail_light, R.drawable.edt_rail_color,
                R.drawable.edt_rail_tool};
        for (int i = 0; i < 3; i++) {
            final int which = i;
            FrameLayout cell = new FrameLayout(this);
            cell.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            // 官方 tab_adjust_background：1dp 灰竖线居中，压在图标底下
            View line = new View(this);
            line.setBackgroundColor(EDT_LINE);
            FrameLayout.LayoutParams lineLp = new FrameLayout.LayoutParams(
                    dp(1), ViewGroup.LayoutParams.MATCH_PARENT);
            lineLp.gravity = Gravity.CENTER_HORIZONTAL;
            line.setLayoutParams(lineLp);
            cell.addView(line);

            // 官方 tab_selected_bg：选中时那根 1dp×40dp 白条
            View marker = new View(this);
            marker.setBackgroundColor(EDT_WHITE);
            FrameLayout.LayoutParams markerLp = new FrameLayout.LayoutParams(dp(1), dp(40));
            markerLp.gravity = Gravity.CENTER;
            marker.setLayoutParams(markerLp);
            marker.setVisibility(View.INVISIBLE);
            cell.addView(marker);
            railMarkers[i] = marker;

            ImageView icon = new ImageView(this);
            icon.setImageResource(railIconsRes[i]);
            icon.setScaleType(ImageView.ScaleType.CENTER);
            FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(dp(27), dp(27));
            iconLp.gravity = Gravity.CENTER;
            icon.setLayoutParams(iconLp);
            cell.addView(icon);
            railIcons[i] = icon;

            cell.setOnClickListener(v -> {
                if (adjustPanel != PANEL_ADJUST) {
                    showAdjustPanel(PANEL_ADJUST);
                }
                selectRail(which);
            });
            rail.addView(cell);
        }
        body.addView(rail);

        ScrollView scroller = new ScrollView(this);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        adjustList = new LinearLayout(this);
        adjustList.setOrientation(LinearLayout.VERTICAL);
        adjustList.setPadding(0, dp(10), 0, dp(14));
        scroller.addView(adjustList, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(scroller);

        return body;
    }

    private void selectRail(int which) {
        railTab = which;
        if (railMarkers != null) {
            for (int i = 0; i < railMarkers.length; i++) {
                railMarkers[i].setVisibility(i == which ? View.VISIBLE : View.INVISIBLE);
            }
        }
        refillAdjustList();
    }

    private void showAdjustPanel(int mode) {
        adjustPanel = mode;
        if (panelButtons != null) {
            for (View v : panelButtons) {
                v.setVisibility(mode == PANEL_ADJUST ? View.VISIBLE : View.GONE);
            }
        }
        if (panelDoneButton != null) {
            panelDoneButton.setVisibility(mode == PANEL_ADJUST ? View.GONE : View.VISIBLE);
        }
        if (adjustTitle != null) {
            adjustTitle.setText(mode == PANEL_HSL ? "HSL"
                    : (mode == PANEL_SPLIT ? "色调分离" : "调整"));
        }
        refillAdjustList();
    }

    /** 按当前轨道/模式重建右边的滑杆 */
    private void refillAdjustList() {
        if (adjustList == null) {
            return;
        }
        adjustList.removeAllViews();
        adjustSynced.clear();
        if (adjustPanel == PANEL_HSL) {
            buildHslContent();
            return;
        }
        if (adjustPanel == PANEL_SPLIT) {
            buildSplitContent();
            return;
        }
        if (railTab == RAIL_LIGHT) {
            addSliderRow("曝光", true, () -> state.exposure, v -> {
                state.exposure = v;
                applyAdjustments();
            });
            addSliderRow("对比度", true, () -> state.contrast, v -> {
                state.contrast = v;
                applyAdjustments();
            });
            addSliderRow("突出显示", true, () -> state.highlight, v -> {
                state.highlight = v;
                applyAdjustments();
            });
            addSliderRow("阴影", true, () -> state.shadow, v -> {
                state.shadow = v;
                applyAdjustments();
            });
            addSliderRow("白电平", true, () -> state.whiteLevel, v -> {
                state.whiteLevel = v;
                applyAdjustments();
            });
            addSliderRow("黑电平", true, () -> state.blackLevel, v -> {
                state.blackLevel = v;
                applyAdjustments();
            });
        } else if (railTab == RAIL_COLOR) {
            addSliderRow("白平衡", true, () -> state.temperature, v -> {
                state.temperature = v;
                applyAdjustments();
            });
            addSliderRow("色调", true, () -> state.tint, v -> {
                state.tint = v;
                applyAdjustments();
            });
            addSliderRow("自然饱和度", true, () -> state.vibrance, v -> {
                state.vibrance = v;
                applyAdjustments();
            });
            addSliderRow("饱和度", true, () -> state.saturation, v -> {
                state.saturation = v;
                applyAdjustments();
            });
        } else {
            addGroupTitle("细节");
            addSliderRow("清晰度", false, () -> state.sharpness, v -> {
                state.sharpness = v;
                applyAdjustments();
            });
            addSliderRow("降噪", false, () -> state.noiseReduction, v -> {
                state.noiseReduction = v;
                applyAdjustments();
            });
            addSliderRow("紫边去除", false, () -> state.rpf, v -> {
                state.rpf = v;
                applyAdjustments();
            });
            addGroupTitle("效果");
            addSliderRow("颗粒效果", false, () -> state.grain, v -> {
                state.grain = v;
                applyAdjustments();
            });
            addSliderRow("彩色噪点", false, () -> state.colorNoise, v -> {
                state.colorNoise = v;
                applyAdjustments();
            });
            addSliderRow("暗角强度", false, () -> state.vignette, v -> {
                state.vignette = v;
                applyAdjustments();
            });
            addGroupTitle("其它");
            addEntryRow("色调曲线", v -> curveOverlay.setVisibility(View.VISIBLE));
            addEntryRow("HSL", v -> showAdjustPanel(PANEL_HSL));
            addEntryRow("色调分离", v -> showAdjustPanel(PANEL_SPLIT));
        }
    }

    private void addGroupTitle(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tv.setTextColor(EDT_TEXT_WEAK);
        tv.setPadding(dp(25), dp(8), dp(25), 0);
        adjustList.addView(tv);
    }

    /** 一行「进下一级」的入口，长得和官方那条工具行一致 */
    private void addEntryRow(String text, View.OnClickListener listener) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTextColor(EDT_TEXT);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setPadding(dp(25), 0, dp(25), 0);
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        tv.setOnClickListener(listener);
        adjustList.addView(tv);
    }

    /** HSL：上面 8 个色板，下面 色彩/饱和度/辉度 三条 */
    private void buildHslContent() {
        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(12), 0, dp(12), 0);

        for (int i = 0; i < EditState.HSL_COLORS; i++) {
            final int index = i;
            LinearLayout chip = new LinearLayout(this);
            chip.setOrientation(LinearLayout.VERTICAL);
            chip.setGravity(Gravity.CENTER_HORIZONTAL);
            chip.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(46)));

            TextView value = new TextView(this);
            value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            value.setTextColor(hslColor == i ? EDT_WHITE : EDT_TEXT_SUB);
            value.setText(String.valueOf(Math.round(state.hsl[i * 3] * 100)));
            value.setIncludeFontPadding(false);
            chip.addView(value);

            FrameLayout ring = new FrameLayout(this);
            ring.setLayoutParams(new LinearLayout.LayoutParams(dp(32), dp(32)));

            android.graphics.drawable.GradientDrawable outer =
                    new android.graphics.drawable.GradientDrawable();
            outer.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            outer.setColor(EDT_WHITE);
            View outerView = new View(this);
            outerView.setBackground(outer);
            FrameLayout.LayoutParams outerLp = new FrameLayout.LayoutParams(dp(32), dp(32));
            outerView.setLayoutParams(outerLp);
            ring.addView(outerView);

            android.graphics.drawable.GradientDrawable inner =
                    new android.graphics.drawable.GradientDrawable();
            inner.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            inner.setColor(HSL_RGB[i]);
            View innerView = new View(this);
            innerView.setBackground(inner);
            FrameLayout.LayoutParams innerLp = new FrameLayout.LayoutParams(
                    dp(hslColor == i ? 26 : 24), dp(hslColor == i ? 26 : 24));
            innerLp.gravity = Gravity.CENTER;
            innerView.setLayoutParams(innerLp);
            ring.addView(innerView);

            chip.addView(ring);
            chip.setOnClickListener(v -> {
                hslColor = index;
                refillAdjustList();
            });
            row.addView(chip);
        }
        scroller.addView(row, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)));
        adjustList.addView(scroller);

        final int base = hslColor * EditState.HSL_STRIDE;
        addSliderRow("色彩", true, () -> state.hsl[base], v -> {
            state.hsl[base] = v;
            applyAdjustments();
        });
        addSliderRow("饱和度", true, () -> state.hsl[base + 1], v -> {
            state.hsl[base + 1] = v;
            applyAdjustments();
        });
        addSliderRow("辉度", true, () -> state.hsl[base + 2], v -> {
            state.hsl[base + 2] = v;
            applyAdjustments();
        });
    }

    /** 色调分离：三个区段 + 色相/饱和度 */
    private void buildSplitContent() {
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(dp(12), 0, dp(12), 0);
        for (int i = 0; i < EditState.SPLIT_RANGES; i++) {
            final int index = i;
            TextView tv = new TextView(this);
            tv.setText(SPLIT_NAMES[i]);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            tv.setGravity(Gravity.CENTER);
            boolean on = splitRange == i;
            tv.setTextColor(on ? EDT_BG : EDT_TEXT_SUB);
            android.graphics.drawable.GradientDrawable bg =
                    new android.graphics.drawable.GradientDrawable();
            bg.setCornerRadius(dp(14));
            bg.setColor(on ? EDT_WHITE : 0xFF2A2A2A);
            tv.setBackground(bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(72), dp(30));
            lp.rightMargin = dp(8);
            tv.setLayoutParams(lp);
            tv.setOnClickListener(v -> {
                splitRange = index;
                refillAdjustList();
            });
            chips.addView(tv);
        }
        adjustList.addView(chips, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));

        final int base = splitRange * EditState.SPLIT_STRIDE;
        addSliderRow("色相", false, () -> state.splitTone[base], v -> {
            state.splitTone[base] = v;
            applyAdjustments();
        });
        addSliderRow("饱和度", false, () -> state.splitTone[base + 1], v -> {
            state.splitTone[base + 1] = v;
            applyAdjustments();
        });
    }

    private interface FloatGetter {
        float get();
    }

    private interface FloatSetter {
        void set(float value);
    }

    /**
     * 官方那一条滑杆：标签左上、数值右上、20dp 滑杆 + 8dp 正中白点，整行 60dp。
     * bipolar=true 是中点为 0 的双向滑杆（曝光那种），false 是 0..满 的单向滑杆。
     */
    private void addSliderRow(String label, boolean bipolar, FloatGetter getter,
                              FloatSetter setter) {
        RelativeLayout row = new RelativeLayout(this);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(60)));

        final int holderId = View.generateViewId();

        TextView title = new TextView(this);
        title.setText(label);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        title.setTextColor(EDT_TEXT_SUB);
        RelativeLayout.LayoutParams titleLp = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.setMarginStart(dp(25));
        titleLp.addRule(RelativeLayout.ALIGN_PARENT_START);
        titleLp.addRule(RelativeLayout.ABOVE, holderId);
        row.addView(title, titleLp);

        TextView value = new TextView(this);
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        value.setTextColor(EDT_TEXT);
        RelativeLayout.LayoutParams valueLp = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        valueLp.setMarginEnd(dp(25));
        valueLp.addRule(RelativeLayout.ALIGN_PARENT_END);
        valueLp.addRule(RelativeLayout.ABOVE, holderId);
        row.addView(value, valueLp);

        RelativeLayout holder = new RelativeLayout(this);
        holder.setId(holderId);
        RelativeLayout.LayoutParams holderLp = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(20));
        holderLp.setMarginStart(dp(15));
        holderLp.setMarginEnd(dp(15));
        holderLp.bottomMargin = dp(12);
        holderLp.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        row.addView(holder, holderLp);

        CenterSeekBar bar = new CenterSeekBar(this);
        bar.setMax(bipolar ? 200 : 100);
        bar.setTrackColor(EDT_TRACK);
        bar.setFillColor(EDT_WHITE);
        bar.setLayoutParams(new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        holder.addView(bar);

        if (bipolar) {
            // 官方那个 8dp 白色中点，提示「归零在这儿」
            View dot = new View(this);
            android.graphics.drawable.GradientDrawable dotBg =
                    new android.graphics.drawable.GradientDrawable();
            dotBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            dotBg.setColor(EDT_WHITE);
            dot.setBackground(dotBg);
            RelativeLayout.LayoutParams dotLp = new RelativeLayout.LayoutParams(dp(8), dp(8));
            dotLp.addRule(RelativeLayout.CENTER_IN_PARENT);
            holder.addView(dot, dotLp);
        }

        Runnable sync = () -> {
            float v = getter.get();
            if (bipolar) {
                float clamped = Math.max(-1f, Math.min(1f, v));
                bar.setProgressSilently(Math.round(100f + clamped * 100f));
                value.setText(formatSigned(clamped));
            } else {
                float clamped = Math.max(0f, Math.min(1f, v));
                bar.setProgressSilently(Math.round(clamped * 100f));
                value.setText(String.valueOf(Math.round(clamped * 100)));
            }
        };

        bar.setOnChangeListener(new CenterSeekBar.OnChangeListener() {
            @Override
            public void onStart() {
                commit();
            }

            @Override
            public void onChanged(int progress, boolean fromUser) {
                if (bipolar) {
                    float v = (progress - 100) / 100f;
                    value.setText(formatSigned(v));
                    setter.set(v);
                } else {
                    value.setText(String.valueOf(progress));
                    setter.set(progress / 100f);
                }
            }

            @Override
            public void onStop() {
            }
        });

        sync.run();
        adjustSynced.add(sync);
        adjustList.addView(row);
    }

    /** 官方那排数值是 -100..+100 的整数，带符号 */
    private static String formatSigned(float value) {
        int iv = Math.round(value * 100f);
        if (iv == 0) {
            return "0";
        }
        return (iv > 0 ? "+" : "") + iv;
    }

    private void syncAdjustSliders() {
        for (Runnable sync : adjustSynced) {
            sync.run();
        }
    }

    private void applyAdjustments() {
        preview.setAdjust(state.packLight(), state.packColor());
        preview.setDetail(state.packDetail());
        preview.setNoise(state.packEffect());
        preview.setHsl(state.packHsl());
        preview.setSplit(state.packSplit());
    }

    /** 点图标：共享直接导，其余四个切换抽屉 */
    private void onTabIconClick(int index) {
        if (index == TAB_SHARE) {
            exportFromTopBar();
            return;
        }
        if (activeTab == index && sheetExpanded) {
            setSheetExpanded(false);
            return;
        }
        selectTab(index);
        setSheetExpanded(true);
    }

    private void setSheetExpanded(boolean expanded) {
        sheetExpanded = expanded;
        if (sheetHost != null) {
            ViewGroup.LayoutParams lp = sheetHost.getLayoutParams();
            lp.height = expanded ? dp(SHEET_HEIGHT) : 0;
            sheetHost.setLayoutParams(lp);
        }
        if (sheetHandle != null) {
            sheetHandle.setVisibility(expanded ? View.VISIBLE : View.GONE);
        }
        updateTabColors();
    }

    private void updateTabColors() {
        if (tabButtons == null) {
            return;
        }
        // 官方是换图，不是加滤镜：选中态有自己的 PNG
        for (int i = 0; i < tabButtons.length; i++) {
            if (tabButtons[i] == null) {
                continue;
            }
            boolean on = (i == activeTab && sheetExpanded);
            tabButtons[i].setImageResource(on ? TAB_ICONS_ON[i] : TAB_ICONS[i]);
        }
    }

    private void exportFromTopBar() {
        if (currentUri != null) {
            exportImage();
        } else if (videoFile != null && videoFile.exists()) {
            exportVideo();
        } else {
            Toast.makeText(this, "先选照片或者视频", Toast.LENGTH_SHORT).show();
        }
    }

    private void selectTab(int index) {        if (sheets == null || index < 0 || index >= sheets.length) {
            return;
        }
        activeTab = index;
        for (int i = 0; i < sheets.length; i++) {
            sheets[i].setVisibility(i == index ? View.VISIBLE : View.GONE);
        }
        // 底栏选中态直接换图（官方就是这么干的），不叠滤镜
        updateTabColors();
    }

    /** 相框 tab */
    private View buildFrameSheet() {
        LinearLayout content = newSheet();
        addSectionTitle(content, "相框");
        frameRow = new LinearLayout(this);
        frameRow.setOrientation(LinearLayout.HORIZONTAL);
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(frameRow);
        content.addView(scroll);

        TextView hint = new TextView(this);
        hint.setText("细白边和内缩一圈留白最稳；底部留白那款会空出一条带子，"
                + "适合放签名或标题。");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        hint.setTextColor(TEXT_DIM);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintParams.topMargin = dp(10);
        hint.setLayoutParams(hintParams);
        content.addView(hint);
        return wrapSheet(content);
    }

    /** LUT tab：选素材、选 LUT、浓度 */
    private View buildLutSheet() {
        LinearLayout content = newSheet();

        LinearLayout buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        TextView pickPhoto = makeButton(getString(R.string.edit_pick_photo), CHIP_BG);
        pickPhoto.setOnClickListener(v -> pickPhoto());
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        half.rightMargin = dp(8);
        pickPhoto.setLayoutParams(half);
        buttonRow.addView(pickPhoto);

        TextView pickLut = makeButton(getString(R.string.edit_pick_lut), CHIP_BG);
        pickLut.setOnClickListener(v -> pickLut());
        pickLut.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        buttonRow.addView(pickLut);
        content.addView(buttonRow);



        lutLabel = new TextView(this);
        lutLabel.setText(getString(R.string.edit_lut_slot, getString(R.string.edit_lut_none)));
        lutLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        lutLabel.setTextColor(ACCENT);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelParams.topMargin = dp(12);
        lutLabel.setLayoutParams(labelParams);
        content.addView(lutLabel);
        lutLabel2 = new TextView(this);
        lutLabel2.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        lutLabel2.setTextColor(ACCENT);
        LinearLayout.LayoutParams l2p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        l2p.topMargin = dp(8);
        lutLabel2.setLayoutParams(l2p);
        lutLabel2.setText("LUT 2: 未选（点这里切换）");
        lutLabel2.setOnClickListener(v -> switchLutSlot(2));
        content.addView(lutLabel2);
        lutLabel.setOnClickListener(v -> switchLutSlot(1));

        sourceLabel = new TextView(this);
        sourceLabel.setText(R.string.edit_source_unknown);
        sourceLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        sourceLabel.setTextColor(TEXT_DIM);
        LinearLayout.LayoutParams sourceParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sourceParams.topMargin = dp(6);
        sourceLabel.setLayoutParams(sourceParams);
        sourceLabel.setOnClickListener(v -> cycleSourceMode());
        content.addView(sourceLabel);

        LinearLayout intensityRow = new LinearLayout(this);
        intensityRow.setOrientation(LinearLayout.HORIZONTAL);
        intensityRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams intensityRowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        intensityRowParams.topMargin = dp(10);
        intensityRow.setLayoutParams(intensityRowParams);

        TextView intensityLabel = new TextView(this);
        intensityLabel.setText(R.string.edit_intensity);
        intensityLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        intensityLabel.setTextColor(TEXT_DIM);
        intensityLabel.setLayoutParams(new LinearLayout.LayoutParams(dp(48),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        intensityRow.addView(intensityLabel);

        intensityBar = new CenterSeekBar(this);
        intensityBar.setMax(100);
        intensityBar.setProgressSilently(100);
        intensityBar.setTrackColor(EDT_TRACK);
        intensityBar.setFillColor(EDT_WHITE);
        intensityBar.setLayoutParams(new LinearLayout.LayoutParams(0, dp(24), 1f));
        intensityBar.setOnChangeListener(new CenterSeekBar.OnChangeListener() {
            @Override
            public void onStart() {
                commit();
            }

            @Override
            public void onChanged(int progress, boolean fromUser) {
                state.lutIntensity = progress / 100f;
                preview.setIntensity(state.lutIntensity);
            }

            @Override
            public void onStop() {
            }
        });
        intensityRow.addView(intensityBar);
        content.addView(intensityRow);

        // LUT 2 的浓度：和 LUT 1 完全独立，各拖各的
        LinearLayout intensityRow2 = new LinearLayout(this);
        intensityRow2.setOrientation(LinearLayout.HORIZONTAL);
        intensityRow2.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams ir2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ir2.topMargin = dp(10);
        intensityRow2.setLayoutParams(ir2);

        TextView intensityLabel2 = new TextView(this);
        intensityLabel2.setText("LUT 2 浓度");
        intensityLabel2.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        intensityLabel2.setTextColor(TEXT_DIM);
        intensityLabel2.setLayoutParams(new LinearLayout.LayoutParams(dp(74),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        intensityRow2.addView(intensityLabel2);

        intensityBar2 = new CenterSeekBar(this);
        intensityBar2.setMax(100);
        intensityBar2.setProgressSilently(100);
        intensityBar2.setTrackColor(EDT_TRACK);
        intensityBar2.setFillColor(EDT_WHITE);
        intensityBar2.setLayoutParams(new LinearLayout.LayoutParams(0, dp(24), 1f));
        intensityBar2.setOnChangeListener(new CenterSeekBar.OnChangeListener() {
            @Override
            public void onStart() {
                commit();
            }

            @Override
            public void onChanged(int progress, boolean fromUser) {
                preview.setIntensity2(progress / 100f);
            }

            @Override
            public void onStop() {
            }
        });
        intensityRow2.addView(intensityBar2);
        content.addView(intensityRow2);

        addSectionTitle(content, "我的 LUT");
        libraryRow = new LinearLayout(this);
        libraryRow.setOrientation(LinearLayout.HORIZONTAL);
        HorizontalScrollView libraryScroll = new HorizontalScrollView(this);
        libraryScroll.setHorizontalScrollBarEnabled(false);
        libraryScroll.addView(libraryRow);
        content.addView(libraryScroll);

        addSectionTitle(content, "视频");
        TextView pickVideo = makeButton("选视频（套 LUT + 变速）", CHIP_BG);
        LinearLayout.LayoutParams pvParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        pickVideo.setLayoutParams(pvParams);
        pickVideo.setOnClickListener(v -> pickVideo());
        content.addView(pickVideo);

        videoLabel = new TextView(this);
        videoLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        videoLabel.setTextColor(TEXT_DIM);
        LinearLayout.LayoutParams vlParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vlParams.topMargin = dp(8);
        videoLabel.setLayoutParams(vlParams);
        videoLabel.setVisibility(View.GONE);
        content.addView(videoLabel);

        speedRow = new LinearLayout(this);
        speedRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams srParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        srParams.topMargin = dp(6);
        speedRow.setLayoutParams(srParams);
        speedRow.setVisibility(View.GONE);
        content.addView(speedRow);

        curveRow = new LinearLayout(this);
        curveRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams crParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        crParams.topMargin = dp(6);
        curveRow.setLayoutParams(crParams);
        curveRow.setVisibility(View.GONE);
        content.addView(curveRow);

        exportVideoBtn = makeButton("导出视频", ACCENT);
        LinearLayout.LayoutParams evParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        evParams.topMargin = dp(8);
        exportVideoBtn.setLayoutParams(evParams);
        exportVideoBtn.setVisibility(View.GONE);
        exportVideoBtn.setOnClickListener(v -> exportVideo());
        content.addView(exportVideoBtn);

        previewVideoEntry = makeButton("▶ 预览原片（不含 LUT）", CHIP_BG);
        LinearLayout.LayoutParams pvbParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
        pvbParams.topMargin = dp(6);
        previewVideoEntry.setLayoutParams(pvbParams);
        previewVideoEntry.setVisibility(View.GONE);
        previewVideoEntry.setOnClickListener(v -> previewVideo());
        content.addView(previewVideoEntry);

        return wrapSheet(content);
    }

    /** 全屏播原片。播放器套不上 GL 链，所以这里只用来确认内容 */
    private void previewVideo() {
        if (videoFile == null || !videoFile.exists()) {
            Toast.makeText(this, "先选一个视频", Toast.LENGTH_SHORT).show();
            return;
        }
        if (videoOverlay != null) {
            return;
        }
        final android.widget.VideoView player = new android.widget.VideoView(this);
        player.setVideoPath(videoFile.getAbsolutePath());
        player.setOnCompletionListener(mp -> hideVideoOverlay());
        player.setOnErrorListener((mp, what, extra) -> {
            hideVideoOverlay();
            Toast.makeText(this, "播放失败", Toast.LENGTH_SHORT).show();
            return true;
        });

        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0xFF000000);  // 播放器底，保持纯黑
        overlay.addView(player, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView hint = new TextView(this);
        hint.setText("点任意处关闭 · 预览不套 LUT");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        hint.setTextColor(0xCCFFFFFF);
        hint.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams hintParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintParams.gravity = Gravity.BOTTOM;
        hintParams.bottomMargin = dp(48);
        hint.setLayoutParams(hintParams);
        overlay.addView(hint);

        overlay.setOnClickListener(v -> hideVideoOverlay());
        videoOverlay = overlay;

        ViewGroup content = findViewById(android.R.id.content);
        content.addView(overlay, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        player.start();
    }

    private void hideVideoOverlay() {
        if (videoOverlay == null) {
            return;
        }
        ViewGroup parent = (ViewGroup) videoOverlay.getParent();
        if (parent != null) {
            parent.removeView(videoOverlay);
        }
        videoOverlay = null;
    }

    // ---------- 视频 ----------

    private void pickVideo() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("video/*");
        try {
            startActivityForResult(intent, REQ_VIDEO);
        } catch (Exception e) {
            Toast.makeText(this, "打不开系统选择器", Toast.LENGTH_SHORT).show();
        }
    }

    /** ffmpeg 只认文件路径，所以先落到私有目录；顺手取首帧当预览 */
    private void prepareVideo(Uri uri) {
        Toast.makeText(this, "正在读取视频…", Toast.LENGTH_SHORT).show();
        setTopBarTitle(mediaLabel(uri));
        new Thread(() -> {
            try {
                java.io.File dir = new java.io.File(getCacheDir(), "video");
                if (!dir.exists() && !dir.mkdirs()) {
                    throw new IOException("建不了缓存目录");
                }
                java.io.File input = new java.io.File(dir, "input.mp4");
                try (InputStream src = SourceLoader.open(this, uri);
                     OutputStream dst = new java.io.FileOutputStream(input)) {
                    byte[] buffer = new byte[1 << 16];
                    int read;
                    while ((read = src.read(buffer)) > 0) {
                        dst.write(buffer, 0, read);
                    }
                }

                long durationMs = 0;
                int frameWidth = 0;
                int frameHeight = 0;
                Bitmap frame = null;
                android.media.MediaMetadataRetriever retriever =
                        new android.media.MediaMetadataRetriever();
                try {
                    retriever.setDataSource(input.getAbsolutePath());
                    String d = retriever.extractMetadata(
                            android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
                    if (d != null) {
                        durationMs = Long.parseLong(d);
                    }
                    String w = retriever.extractMetadata(
                            android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                    String h = retriever.extractMetadata(
                            android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                    if (w != null) {
                        frameWidth = Integer.parseInt(w);
                    }
                    if (h != null) {
                        frameHeight = Integer.parseInt(h);
                    }
                    frame = retriever.getFrameAtTime(0);
                } finally {
                    try {
                        retriever.release();
                    } catch (Exception ignored) {
                    }
                }

                final long finalDuration = durationMs;
                final int finalWidth = frameWidth;
                final int finalHeight = frameHeight;
                final Bitmap finalFrame = frame;
                runOnUiThread(() -> {
                    videoFile = input;
                    videoDurationMs = finalDuration;
                    videoWidth = finalWidth;
                    videoHeight = finalHeight;
                    if (finalFrame != null) {
                        preview.setImage(finalFrame);
                    }
                    videoLabel.setText(String.format(java.util.Locale.US,
                            "%s · %d.%01d 秒 · 已载入，套的是当前 LUT",
                            input.getName(), finalDuration / 1000,
                            (finalDuration % 1000) / 100));
                    videoLabel.setVisibility(View.VISIBLE);
                    speedRow.setVisibility(View.VISIBLE);
                    curveRow.setVisibility(View.VISIBLE);
                    exportVideoBtn.setVisibility(View.VISIBLE);
                    previewVideoEntry.setVisibility(View.VISIBLE);
                    rebuildSpeedChips();
                    rebuildCurveChips();
                });
            } catch (Throwable t) {
                Log.e("EditActivity", "读视频失败", t);
                runOnUiThread(() -> Toast.makeText(this,
                        getString(R.string.edit_failed, String.valueOf(t.getMessage())),
                        Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void rebuildSpeedChips() {
        if (speedRow == null) {
            return;
        }
        float[] presets = io.github.lumixlut.edit.video.ConstantSpeedPlan.PRESETS;
        String[] labels = new String[presets.length];
        int selected = -1;
        for (int i = 0; i < presets.length; i++) {
            labels[i] = io.github.lumixlut.edit.video.ConstantSpeedPlan.labelOf(presets[i]);
            if (curvePlan == null && Math.abs(presets[i] - videoSpeed) < 0.001f) {
                selected = i;
            }
        }
        fillChips(speedRow, labels, selected, index -> {
            videoSpeed = presets[index];
            // 选了固定档就把曲线让出来
            curvePlan = null;
            curvePresetIndex = -1;
            rebuildSpeedChips();
            rebuildCurveChips();
        });
    }

    private void rebuildCurveChips() {
        if (curveRow == null) {
            return;
        }
        String[] labels = {"慢入快出", "快入慢出", "中间最快"};
        fillChips(curveRow, labels, curvePresetIndex, index -> {
            if (curvePresetIndex == index) {
                // 再点一次取消，回到固定档
                curvePresetIndex = -1;
                curvePlan = null;
            } else {
                curvePresetIndex = index;
                curvePlan = index == 0
                        ? io.github.lumixlut.edit.video.CurveSpeedPlan.easeIn()
                        : index == 1
                        ? io.github.lumixlut.edit.video.CurveSpeedPlan.easeOut()
                        : io.github.lumixlut.edit.video.CurveSpeedPlan.peak();
            }
            rebuildCurveChips();
            rebuildSpeedChips();
        });
    }

    private void exportVideo() {
        if (videoFile == null || !videoFile.exists()) {
            Toast.makeText(this, "先选一个视频", Toast.LENGTH_SHORT).show();
            return;
        }
        java.io.File dir = new java.io.File(getCacheDir(), "video");
        java.io.File output = new java.io.File(dir, "output.mp4");
        if (output.exists() && !output.delete()) {
            Log.w("EditActivity", "旧的输出删不掉");
        }
        Toast.makeText(this, "正在转码，别退出…", Toast.LENGTH_LONG).show();

        io.github.lumixlut.edit.video.VideoEngine.Callback done = (ok, message) -> {
            if (ok) {
                saveVideoToGallery(output);
            } else {
                runOnUiThread(() -> Toast.makeText(this,
                        "转码失败：" + message, Toast.LENGTH_LONG).show());
            }
        };

        if (curvePlan != null) {
            // 曲线变速：整条静音，按 12 段切分重打时间戳
            io.github.lumixlut.edit.video.VideoEngine.transcodeCurve(
                    videoFile, currentLutFile, curvePlan, videoDurationMs,
                    cropAspect, videoWidth, videoHeight, output, done);
        } else {
            io.github.lumixlut.edit.video.SpeedPlan plan =
                    new io.github.lumixlut.edit.video.ConstantSpeedPlan(videoSpeed);
            io.github.lumixlut.edit.video.VideoEngine.transcode(
                    videoFile, currentLutFile, plan,
                    cropAspect, videoWidth, videoHeight, output, done);
        }
    }

    private void saveVideoToGallery(java.io.File video) {
        new Thread(() -> {
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Video.Media.DISPLAY_NAME,
                        "LUT_" + System.currentTimeMillis() + ".mp4");
                values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                values.put(MediaStore.Video.Media.DATE_ADDED,
                        System.currentTimeMillis() / 1000);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.put(MediaStore.Video.Media.RELATIVE_PATH,
                            Environment.DIRECTORY_MOVIES + "/LUT搜索器");
                }
                Uri outUri = getContentResolver().insert(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
                if (outUri == null) {
                    throw new IOException("插不进 MediaStore");
                }
                try (InputStream in = new java.io.FileInputStream(video);
                     OutputStream os = getContentResolver().openOutputStream(outUri)) {
                    if (os == null) {
                        throw new IOException("拿不到输出流");
                    }
                    byte[] buffer = new byte[1 << 16];
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        os.write(buffer, 0, read);
                    }
                }
                long kb = video.length() / 1024;
                runOnUiThread(() -> Toast.makeText(this,
                        "视频已保存到相册（" + kb + " KB）", Toast.LENGTH_LONG).show());
            } catch (Throwable t) {
                Log.e("EditActivity", "保存视频失败", t);
                runOnUiThread(() -> Toast.makeText(this,
                        getString(R.string.edit_failed, String.valueOf(t.getMessage())),
                        Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    /** 后台读下载表并烘缩略图，读完一次性铺进去 */
    private void refreshLibrary() {
        if (libraryRow == null) {
            return;
        }
        final int[] reference = referencePixels;
        new Thread(() -> {
            java.util.List<LutLibrary.Entry> list = new java.util.ArrayList<>();
            try {
                list = LutLibrary.list(this);
                for (LutLibrary.Entry entry : list) {
                    entry.thumb = LutLibrary.thumb(this, entry, reference);
                }
            } catch (Throwable t) {
                Log.w("EditActivity", "读取 LUT 库失败：" + t.getMessage());
            }
            final java.util.List<LutLibrary.Entry> result = list;
            runOnUiThread(() -> fillLibrary(result));
        }).start();
    }

    /** 从当前照片裁一张 216×216 的参考图，缩略图用它当底 */
    private void rebuildReference() {
        referencePixels = LutThumb.squareReference(currentBitmap, 216);
    }

    private void fillLibrary(java.util.List<LutLibrary.Entry> list) {
        if (libraryRow == null) {
            return;
        }
        libraryRow.removeAllViews();
        if (list.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("还没有下载过 LUT。去搜索页下几条，或者点上面的「选 LUT」直接挑文件。");
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            empty.setTextColor(TEXT_DIM);
            empty.setPadding(dp(2), dp(6), dp(2), dp(6));
            libraryRow.addView(empty);
            return;
        }
        for (LutLibrary.Entry entry : list) {
            libraryRow.addView(makeLibraryItem(entry));
        }
    }

    private View makeLibraryItem(LutLibrary.Entry entry) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        item.setPadding(dp(4), 0, dp(8), 0);

        int edge = dp(72);
        ImageView thumb = new ImageView(this);
        thumb.setLayoutParams(new LinearLayout.LayoutParams(edge, edge));
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        // 圆角靠 clipToOutline + 圆角背景，不需要额外依赖
        android.graphics.drawable.GradientDrawable shape =
                new android.graphics.drawable.GradientDrawable();
        shape.setCornerRadius(dp(8));
        shape.setColor(CHIP_BG);
        thumb.setBackground(shape);
        thumb.setClipToOutline(true);
        if (entry.thumb != null) {
            thumb.setImageBitmap(entry.thumb);
        }
        item.addView(thumb);

        TextView label = new TextView(this);
        label.setText(entry.shortLabel());
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        label.setTextColor(TEXT_DIM);
        label.setMaxWidth(edge);
        label.setMaxLines(1);
        label.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelParams.topMargin = dp(4);
        label.setLayoutParams(labelParams);
        item.addView(label);

        item.setOnClickListener(v -> loadLut(entry.uri, activeLutSlot));
        return item;
    }

    /** 调整 tab：光比 / 色彩 / 细节，外加曲线入口和重置 */

    /** 裁剪 tab */
    private View buildCropSheet() {
        LinearLayout content = newSheet();
        addSectionTitle(content, "裁剪比例");
        cropRow = new LinearLayout(this);
        cropRow.setOrientation(LinearLayout.HORIZONTAL);
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(cropRow);
        content.addView(scroll);

        TextView hint = new TextView(this);
        hint.setText("按选中的比例居中裁切。导出时画布会跟着变成同样的比例，"
                + "不会被拉伸。");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        hint.setTextColor(TEXT_DIM);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintParams.topMargin = dp(10);
        hint.setLayoutParams(hintParams);
        content.addView(hint);
        return wrapSheet(content);
    }

    /** 导出 tab */
    private View buildExportSheet() {
        LinearLayout content = newSheet();
        addSectionTitle(content, "导出");

        TextView exportEntry = makeButton("导出到相册", ACCENT);
        LinearLayout.LayoutParams exportParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        exportEntry.setLayoutParams(exportParams);
        exportEntry.setOnClickListener(v -> exportImage());
        content.addView(exportEntry);

        TextView hint = new TextView(this);
        hint.setText("按原图分辨率重跑一遍滤镜链，不是截屏；拍摄信息会一起带上。"
                + "文件落在相册的「LUT搜索器」目录。");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        hint.setTextColor(TEXT_DIM);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintParams.topMargin = dp(10);
        hint.setLayoutParams(hintParams);
        content.addView(hint);
        return wrapSheet(content);
    }

    // ---------- 裁剪 / 相框 ----------

    private static final String[] CROP_LABELS = {"原始", "1:1", "4:3", "3:2", "16:9", "4:5", "9:16"};
    private static final float[] CROP_ASPECTS = {0f, 1f, 4f / 3f, 3f / 2f, 16f / 9f, 4f / 5f, 9f / 16f};
    private static final String[] FRAME_LABELS = {"无", "细白边", "粗黑边", "底部留白"};
    private static final float[] FRAME_INSETS = {0f, 0.018f, 0.045f, 0.13f};

    private interface IntCallback {
        void onPick(int index);
    }

    private TextView[] fillChips(LinearLayout container, String[] labels, int selected,
                                 IntCallback callback) {
        container.removeAllViews();
        TextView[] chips = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView chip = new TextView(this);
            chip.setText(labels[i]);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(12), dp(7), dp(12), dp(7));
            chip.setTextColor(i == selected ? ON_ACCENT : TEXT_DIM);
            chip.setBackgroundColor(i == selected ? ACCENT : CHIP_BG);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.rightMargin = dp(6);
            chip.setLayoutParams(params);
            chip.setOnClickListener(v -> callback.onPick(index));
            container.addView(chip);
            chips[i] = chip;
        }
        return chips;
    }

    private void rebuildCropChips() {
        fillChips(cropRow, CROP_LABELS, cropIndex, index -> {
            if (index == cropIndex) {
                return;
            }
            commit();
            cropIndex = index;
            cropAspect = CROP_ASPECTS[index];
            rebuildCropChips();
            applyCrop();
        });
    }

    private void rebuildFrameChips() {
        fillChips(frameRow, FRAME_LABELS, frameIndex, index -> {
            if (index == frameIndex) {
                return;
            }
            commit();
            frameIndex = index;
            frameType = index;
            state.frameInset = FRAME_INSETS[index];
            rebuildFrameChips();
            applyCrop();
        });
    }

    /** 按选中的比例算居中裁剪区域，再把结果推给渲染线程 */
    private void applyCrop() {
        float scaleX = 1f;
        float scaleY = 1f;
        if (currentBitmap != null && cropAspect > 0.0001f) {
            float w = currentBitmap.getWidth();
            float h = currentBitmap.getHeight();
            if (w / h > cropAspect) {
                scaleX = (h * cropAspect) / w;
            } else {
                scaleY = (w / cropAspect) / h;
            }
        }
        state.cropAspect = cropAspect;
        state.cropScaleX = scaleX;
        state.cropScaleY = scaleY;
        state.cropOffsetX = (1f - scaleX) / 2f;
        state.cropOffsetY = (1f - scaleY) / 2f;
        state.frameType = frameType;
        state.frameInset = FRAME_INSETS[frameIndex];
        preview.setFrame(state.packFrame(), frameType, cropAspect);
    }

    private View buildCurveOverlay() {
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(SCRIM);
        overlay.setVisibility(View.GONE);
        overlay.setClickable(true);
        curveOverlay = overlay;

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(PANEL);
        card.setPadding(dp(14), dp(12), dp(14), dp(14));

        TextView title = new TextView(this);
        title.setText("Tone Curve");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        title.setTextColor(ACCENT);
        card.addView(title);

        curveView = new ToneCurveView(this);
        int edge = getResources().getDisplayMetrics().widthPixels - dp(72);
        LinearLayout.LayoutParams curveParams = new LinearLayout.LayoutParams(edge, edge);
        curveParams.topMargin = dp(10);
        curveParams.gravity = Gravity.CENTER_HORIZONTAL;
        curveView.setLayoutParams(curveParams);
        curveView.setListener(new ToneCurveView.Listener() {
            @Override
            public void onCurveChanged(ToneCurve curve) {
                applyCurves();
            }

            @Override
            public void onEditStart() {
                commit();
            }
        });
        card.addView(curveView);

        LinearLayout channelRow = new LinearLayout(this);
        channelRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams channelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
        channelParams.topMargin = dp(10);
        channelRow.setLayoutParams(channelParams);
        String[] labels = {"主", "R", "G", "B"};
        int[] rows = {ToneCurveSet.ROW_MASTER, ToneCurveSet.ROW_RED,
                ToneCurveSet.ROW_GREEN, ToneCurveSet.ROW_BLUE};
        channelButtons = new TextView[rows.length];
        for (int i = 0; i < rows.length; i++) {
            final int row = rows[i];
            TextView button = makeButton(labels[i], CHIP_BG);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            if (i > 0) {
                params.leftMargin = dp(6);
            }
            button.setLayoutParams(params);
            button.setOnClickListener(v -> selectCurveRow(row));
            channelButtons[i] = button;
            channelRow.addView(button);
        }
        card.addView(channelRow);

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        actionParams.topMargin = dp(10);
        actionRow.setLayoutParams(actionParams);

        TextView reset = makeButton("重置曲线", CHIP_BG);
        reset.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        reset.setOnClickListener(v -> {
            curves.reset();
            curveView.setCurve(curves.byRow(activeCurveRow), colorForRow(activeCurveRow));
            applyCurves();
        });
        actionRow.addView(reset);

        TextView done = makeButton("完成", ACCENT);
        LinearLayout.LayoutParams doneParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        doneParams.leftMargin = dp(8);
        done.setLayoutParams(doneParams);
        done.setOnClickListener(v -> overlay.setVisibility(View.GONE));
        actionRow.addView(done);

        card.addView(actionRow);

        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.gravity = Gravity.CENTER;
        overlay.addView(card, cardParams);
        return overlay;
    }

    private void selectCurveRow(int row) {
        activeCurveRow = row;
        if (curveView != null) {
            curveView.setCurve(curves.byRow(row), colorForRow(row));
        }
        if (channelButtons != null) {
            for (int i = 0; i < channelButtons.length; i++) {
                channelButtons[i].setTextColor(i == row ? TEXT : TEXT_DIM);
                channelButtons[i].setBackgroundColor(
                        i == row ? colorForRow(row) : CHIP_BG);
            }
        }
    }

    private int colorForRow(int row) {
        switch (row) {
            case ToneCurveSet.ROW_RED:
                return 0xFFFF6B6B;
            case ToneCurveSet.ROW_GREEN:
                return 0xFF6BE08C;
            case ToneCurveSet.ROW_BLUE:
                return 0xFF6BB6FF;
            default:
                return TEXT;
        }
    }

    private void applyCurves() {
        preview.setCurve(curves.toTexture(), !curves.isIdentity());
    }

    // ---------- 导出 ----------

    private void exportImage() {
        if (currentUri == null) {
            Toast.makeText(this, "先选一张照片", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "正在导出…", Toast.LENGTH_SHORT).show();
        Uri sourceUri = currentUri;
        new Thread(() -> {
            try {
                // 预览用的是屏幕尺寸的缩略图，导出得重新解一次全分辨率
                Bitmap full = SourceLoader.load(this, sourceUri, 8192);
                preview.export(full, new io.github.lumixlut.edit.gl.GlRenderThread.ExportCallback() {
                    @Override
                    public void onDone(Bitmap bitmap) {
                        saveToGallery(bitmap, sourceUri);
                    }

                    @Override
                    public void onFailed(String message) {
                        runOnUiThread(() -> Toast.makeText(EditActivity.this,
                                getString(R.string.edit_failed, message),
                                Toast.LENGTH_LONG).show());
                    }
                });
            } catch (Exception e) {
                Log.e("EditActivity", "导出准备失败", e);
                runOnUiThread(() -> Toast.makeText(this,
                        getString(R.string.edit_failed, String.valueOf(e.getMessage())),
                        Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void saveToGallery(Bitmap bitmap, Uri sourceUri) {
        new Thread(() -> {
            java.io.File temp = null;
            try {
                String name = "LUT_" + System.currentTimeMillis() + ".jpg";
                // ExifInterface.saveAttributes() 只认可随机访问的文件，
                // 对 content:// 的输出流没辙，所以先落一个临时文件写好 EXIF 再整体拷进去
                temp = new java.io.File(getCacheDir(), "lut_export.jpg");
                try (OutputStream os = new java.io.FileOutputStream(temp)) {
                    // 质量 100，和官方 LUMIX Lab 一致（它那边 C1368b0.b0() 就是 compress(..., 100, ...)）。
                    // 之前用 95，量化表是 [2,1,1,2,2,4,5,6…] 那一档，同一张图体积比官方小一半左右，
                    // 高反差边缘和细颗粒也更容易被抹平 —— 既然是对齐官方，就别在这儿省字节。
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, os);
                }
                copyExif(sourceUri, temp);

                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                values.put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.put(MediaStore.Images.Media.RELATIVE_PATH,
                            Environment.DIRECTORY_PICTURES + "/LUT搜索器");
                }
                Uri outUri = getContentResolver().insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (outUri == null) {
                    throw new IOException("插不进 MediaStore");
                }
                try (InputStream in = new java.io.FileInputStream(temp);
                     OutputStream os = getContentResolver().openOutputStream(outUri)) {
                    if (os == null) {
                        throw new IOException("拿不到输出流");
                    }
                    byte[] buffer = new byte[1 << 16];
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        os.write(buffer, 0, read);
                    }
                }
                bitmap.recycle();
                long kb = temp.length() / 1024;
                runOnUiThread(() -> Toast.makeText(EditActivity.this,
                        "已保存到相册：" + name + "（" + kb + " KB）", Toast.LENGTH_LONG).show());
            } catch (Throwable t) {
                Log.e("EditActivity", "保存失败", t);
                runOnUiThread(() -> Toast.makeText(EditActivity.this,
                        getString(R.string.edit_failed, String.valueOf(t.getMessage())),
                        Toast.LENGTH_LONG).show());
            } finally {
                if (temp != null && temp.exists() && !temp.delete()) {
                    Log.w("EditActivity", "临时文件没删掉：" + temp);
                }
            }
        }).start();
    }

    /** 把原图的拍摄信息抄到临时文件上；方向直接写回正常，因为像素已经摆正了 */
    private void copyExif(Uri sourceUri, java.io.File target) {
        String[] tags = {
                ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL,
                ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME,
                ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_EXPOSURE_TIME,
                ExifInterface.TAG_ISO_SPEED_RATINGS, ExifInterface.TAG_FOCAL_LENGTH,
                ExifInterface.TAG_FLASH, ExifInterface.TAG_WHITE_BALANCE,
                ExifInterface.TAG_APERTURE_VALUE, ExifInterface.TAG_BRIGHTNESS_VALUE,
                ExifInterface.TAG_SCENE_CAPTURE_TYPE,
        };
        try (InputStream srcIn = SourceLoader.open(this, sourceUri)) {
            ExifInterface src = new ExifInterface(srcIn);
            ExifInterface dst = new ExifInterface(target.getAbsolutePath());
            for (String tag : tags) {
                String value = src.getAttribute(tag);
                if (value != null) {
                    dst.setAttribute(tag, value);
                }
            }
            dst.setAttribute(ExifInterface.TAG_ORIENTATION,
                    String.valueOf(ExifInterface.ORIENTATION_NORMAL));
            dst.saveAttributes();
        } catch (Exception e) {
            // EXIF 抄不过去不影响成图，记一笔就够
            Log.w("EditActivity", "EXIF 复制失败：" + e.getMessage());
        }
    }

    /** 记录每条滑杆，重置时统一回零用 */
    private final java.util.List<Runnable> sliderResetters = new java.util.ArrayList<>();
    /** 每条滑杆按索引从 state 里取值回填，顺序见 EditState.packAll 的注释 */
    private final java.util.List<Runnable> sliderAppliers = new java.util.ArrayList<>();
    private int nextSliderIndex;

    private final UndoStack undoStack = new UndoStack();

    // ---------- 草稿 ----------

    /** 有没有值得保存的改动：动过参数就算 */
    private boolean hasUnsavedChanges() {
        return undoStack.undoDepth() > 0 || undoStack.redoDepth() > 0;
    }

    @Override
    public void onBackPressed() {
        if (!hasUnsavedChanges()) {
            // 这一趟没改动，直接退。
            // 注意**不能**在这里清草稿 —— 之前就是这么写的，
            // 结果「打开编辑器看一眼就退出」会把上次存好的草稿一起删掉。
            // 只有用户明确选「不保存」或「重新开始」才该清。
            super.onBackPressed();
            return;
        }
        new AlertDialog.Builder(this)
                .setMessage("保存这次的修改？\n下次进编辑器可以接着改，撤销记录也一起留着。")
                .setPositiveButton("保存", (d, w) -> saveDraftThenExit())
                .setNegativeButton("不保存", (d, w) -> {
                    DraftStore.clear(this);
                    finish();
                })
                .setNeutralButton("继续编辑", null)
                .show();
    }

    private void saveDraftThenExit() {
        Toast.makeText(this, "正在保存草稿…", Toast.LENGTH_SHORT).show();
        final Bitmap source = currentBitmap;
        final java.io.File lut = currentLutFile;
        final EditSnapshot current = snapshot();
        final float aspect = cropAspect;
        final int mode = overrideMode;
        new Thread(() -> {
            try {
                DraftStore.save(this, source, lut, current, undoStack, aspect, mode);
                runOnUiThread(() -> {
                    Toast.makeText(this, "已保存，下次进来还能接着改",
                            Toast.LENGTH_SHORT).show();
                    finish();
                });
            } catch (Throwable t) {
                Log.e("EditActivity", "保存草稿失败", t);
                runOnUiThread(() -> Toast.makeText(this,
                        getString(R.string.edit_failed, String.valueOf(t.getMessage())),
                        Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    /** 冷启动时看看有没有上次没做完的编辑；带明确参数进来的就不问了 */
    private void checkDraftOnLaunch() {
        if (getIntent() != null && getIntent().getStringExtra("image_path") != null) {
            return;
        }
        if (!DraftStore.has(this)) {
            return;
        }
        new AlertDialog.Builder(this)
                .setMessage("上次的编辑还没结束，要接着改吗？")
                .setPositiveButton("接着改", (d, w) -> restoreDraft())
                .setNegativeButton("重新开始", (d, w) -> DraftStore.clear(this))
                .setNeutralButton("稍后", null)
                .show();
    }

    private void restoreDraft() {
        Toast.makeText(this, "正在恢复…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            DraftStore.Draft draft = DraftStore.load(this);
            if (draft == null || draft.sourceFile == null) {
                runOnUiThread(() -> Toast.makeText(this, "草稿读不出来",
                        Toast.LENGTH_SHORT).show());
                return;
            }
            Bitmap bitmap;
            try {
                bitmap = android.graphics.BitmapFactory
                        .decodeFile(draft.sourceFile.getAbsolutePath());
            } catch (Throwable t) {
                bitmap = null;
            }
            LutData lut = null;
            if (draft.lutFile != null) {
                try (InputStream in = new java.io.FileInputStream(draft.lutFile)) {
                    lut = CubeParser.parse(in, draft.lutFile.getName());
                } catch (Throwable t) {
                    Log.w("EditActivity", "草稿里的 LUT 读不出来：" + t.getMessage());
                }
            }
            final Bitmap finalBitmap = bitmap;
            final LutData finalLut = lut;
            // 素材判定也得重跑：草稿只存了像素和参数，没存「这是不是 V-Log」
            SourceProbe probe = null;
            try {
                probe = SourceProbe.probe(this, Uri.fromFile(draft.sourceFile));
            } catch (Throwable t) {
                Log.w("EditActivity", "草稿素材判定失败：" + t.getMessage());
            }
            final SourceProbe finalProbe = probe;
            runOnUiThread(() -> {
                if (finalBitmap == null) {
                    Toast.makeText(this, "草稿里的照片读不出来", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (currentBitmap != null && currentBitmap != finalBitmap) {
                    currentBitmap.recycle();
                }
                currentBitmap = finalBitmap;
                currentUri = Uri.fromFile(draft.sourceFile);
                source = finalProbe;
                preview.setImage(finalBitmap);
                setTopBarTitle(mediaLabel(currentUri));
                if (finalLut != null) {
                    currentLut = finalLut;
                    currentLutFile = draft.lutFile;
                    preview.setLut(finalLut);
                    lutLabel.setText(formatLutLabel(finalLut));
                }
                cropAspect = draft.cropAspect;
                overrideMode = draft.overrideMode;
                if (draft.current != null) {
                    applySnapshot(draft.current);
                }
                // 撤销链一起接回来，不然用户一按撤销就没反应
                undoStack.restore(draft.undo, draft.redo);
                updateUndoButtons();
                applySourceMode();
                applyCrop();
                rebuildReference();
                LutLibrary.invalidateThumbs();
                refreshLibrary();
                Log.d("EditActivity", "草稿已恢复：撤销 " + undoStack.undoDepth()
                        + " 步，重做 " + undoStack.redoDepth() + " 步");
            });
        }).start();
    }
    private ImageView undoButton;
    private ImageView redoButton;

    private TextView makeChipButton(String label, android.view.View.OnClickListener listener) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        view.setGravity(Gravity.CENTER);
        view.setTextColor(TEXT);
        view.setPadding(dp(10), dp(4), dp(10), dp(4));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.leftMargin = dp(4);
        view.setLayoutParams(params);
        view.setOnClickListener(listener);
        return view;
    }

    private EditSnapshot snapshot() {
        return new EditSnapshot(state.packLight(), state.packColor(), state.packDetail(),
                state.packEffect(), state.packFrame(), state.packHsl(), state.packSplit(),
                curves.toSnapshot(), state.frameType, cropIndex,
                state.lutIntensity, state.sourceMode);
    }

    /** 在真正改动之前调用，把"改动前"的状态压栈 */
    private void commit() {
        undoStack.push(snapshot());
        updateUndoButtons();
    }

    private void doUndo() {
        EditSnapshot target = undoStack.undo(snapshot());
        if (target != null) {
            applySnapshot(target);
        }
        updateUndoButtons();
    }

    private void doRedo() {
        EditSnapshot target = undoStack.redo(snapshot());
        if (target != null) {
            applySnapshot(target);
        }
        updateUndoButtons();
    }

    private void applySnapshot(EditSnapshot s) {
        state.restore(s.light, s.color, s.detail, s.effect, s.crop, s.hsl, s.split);
        curves.applySnapshot(s.curvePoints);
        cropIndex = Math.max(0, Math.min(CROP_ASPECTS.length - 1, s.cropIndex));
        cropAspect = CROP_ASPECTS[cropIndex];
        frameIndex = Math.max(0, Math.min(FRAME_INSETS.length - 1, s.frameType));
        frameType = frameIndex;
        state.frameType = frameType;
        state.lutIntensity = s.lutIntensity;
        state.sourceMode = s.sourceMode;

        syncAdjustSliders();
        // HSL / 色调分离的面板内容依赖被选中的色板，重建一次才对准
        refillAdjustList();
        if (intensityBar != null) {
            intensityBar.setProgressSilently(Math.round(Math.max(0f, Math.min(1f, s.lutIntensity)) * 100f));
        }
        rebuildCropChips();
        rebuildFrameChips();
        if (curveView != null) {
            curveView.setCurve(curves.byRow(activeCurveRow), colorForRow(activeCurveRow));
        }

        preview.setIntensity(state.lutIntensity);
        applyAdjustments();
        applyCurves();
        applyCrop();
    }

    private void updateUndoButtons() {
        if (undoButton == null || redoButton == null) {
            return;
        }
        undoButton.setImageResource(undoStack.canUndo()
                ? R.drawable.edt_undo : R.drawable.edt_undo_off);
        redoButton.setImageResource(undoStack.canRedo()
                ? R.drawable.edt_redo : R.drawable.edt_redo_off);
    }

    private void addSectionTitle(LinearLayout parent, String title) {
        TextView view = new TextView(this);
        view.setText(title);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        view.setTextColor(ACCENT);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(14);
        params.bottomMargin = dp(2);
        view.setLayoutParams(params);
        parent.addView(view);
    }


    private TextView makeButton(String text, int background) {
        TextView button = new TextView(this);
        button.setText(text);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        button.setTextColor(TEXT);
        button.setGravity(Gravity.CENTER);
        button.setBackgroundColor(background);
        return button;
    }

    /**
     * 外部拉起时可以用两个 extra 直接喂素材，省掉系统文件选择器：
     *   --es image_path /sdcard/Android/data/<pkg>/files/bars.png
     *   --es lut_path   /sdcard/Android/data/<pkg>/files/teal.cube
     * 第 6 步「从 LUT 详情页一键进编辑」也走这条路，只是那时传的是 content://。
     */
    private void applyIncoming(Intent intent) {
        if (intent == null) {
            return;
        }
        String imagePath = intent.getStringExtra(EXTRA_IMAGE);
        if (imagePath != null && !imagePath.isEmpty()) {
            loadPhoto(Uri.parse(imagePath));
        }
        String lutPath = intent.getStringExtra(EXTRA_LUT);
        if (lutPath != null && !lutPath.isEmpty()) {
            loadLut(Uri.parse(lutPath));
        }
    }

    /** 直接拉相册（ACTION_PICK），不是文件管理器 */
    private void pickPhoto() {
        try {
            Intent intent = new Intent(Intent.ACTION_PICK,
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            intent.setType("image/*");
            startActivityForResult(intent, REQ_PICK_PHOTO);
        } catch (Exception e) {
            // 没有可用的相册应用时退回文件选择器
            Intent fallback = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            fallback.addCategory(Intent.CATEGORY_OPENABLE);
            fallback.setType("image/*");
            try {
                startActivityForResult(fallback, REQ_PICK_PHOTO);
            } catch (Exception e2) {
                Toast.makeText(this, "没有可用的相册", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /** 点素材那一行循环切换：自动 → 转 V-Log → 解 V-Log → 直通 → 自动 */
    private void cycleSourceMode() {
        commit();
        if (overrideMode < 0) {
            overrideMode = 1;
        } else if (overrideMode == 1) {
            overrideMode = 2;
        } else if (overrideMode == 2) {
            overrideMode = 0;
        } else {
            overrideMode = -1;
        }
        applySourceMode();
    }

    /**
     * 自动模式下按「素材」和「LUT」两边的情况决定：
     *
     *   素材 V-Log + 普通 LUT   → 先解 log，否则只会更灰
     *   普通素材 + V-Log LUT    → 先把画面转成 V-Log 再喂给 LUT。
     *                             这条 LUT 的输入域是 0.125~0.6，直通会把
     *                             大部分像素顶到域外，出来就是一片黑
     *   两边一致（都普通 / 都是 log）→ 直通
     *
     * 之前一律直通，是因为只拿 STD 的 LUT 试过；换成声明 VLOG 的 LUT 就翻车了。
     */
    private void applySourceMode() {
        int mode;
        int tagRes;
        if (overrideMode == 1) {
            mode = Lut3DFilter.SOURCE_SRGB_TO_VLOG;
            tagRes = R.string.edit_source_force_vlog;
        } else if (overrideMode == 2) {
            mode = Lut3DFilter.SOURCE_VLOG_TO_SRGB;
            tagRes = R.string.edit_source_force_from_vlog;
        } else if (overrideMode == 0) {
            mode = Lut3DFilter.SOURCE_PASSTHROUGH;
            tagRes = R.string.edit_source_force_passthrough;
        } else {
            boolean sourceIsVLog = source != null && source.kind == SourceProbe.Kind.VLOG;
            boolean lutWantsVLog = currentLut != null
                    && currentLut.targetStyle != null
                    && currentLut.targetStyle.toUpperCase(java.util.Locale.US).contains("VLOG");
            if (sourceIsVLog && !lutWantsVLog) {
                mode = Lut3DFilter.SOURCE_VLOG_TO_SRGB;
                tagRes = R.string.edit_source_auto_from_vlog;
            } else if (!sourceIsVLog && lutWantsVLog) {
                mode = Lut3DFilter.SOURCE_SRGB_TO_VLOG;
                tagRes = R.string.edit_source_auto_vlog;
            } else {
                mode = Lut3DFilter.SOURCE_PASSTHROUGH;
                tagRes = R.string.edit_source_auto_passthrough;
            }
        }
        preview.setSourceMode(mode);

        String base = source == null
                ? getString(R.string.edit_source_unknown)
                : "素材：" + source.label;
        sourceLabel.setText(base + "  ·  " + getString(tagRes) + "  ⟳");
    }

    /** auto / to_vlog(baked) / from_vlog(vlog) / passthrough */
    private static int parseOverride(String value) {
        if (value == null) {
            return -1;
        }
        switch (value) {
            case "to_vlog":
            case "baked":
                return 1;
            case "from_vlog":
            case "vlog":
                return 2;
            case "passthrough":
                return 0;
            default:
                return -1;
        }
    }

    /** LUT 标签带上它声明的目标格调，让用户一眼看到这条 LUT 想要什么素材 */
    private String formatLutLabel(LutData data) {
        String base = getString(R.string.edit_lut_slot, data.title);
        if (data != null && data.targetStyle != null) {
            base = base + "  ·  需 " + data.targetStyle;
        }
        return base;
    }

    /** 切「往哪个槽装 LUT」。选文件和点「我的 LUT」都往当前槽里放 */
    private void switchLutSlot(int slot) {
        activeLutSlot = slot;
        refreshLutSlotStyles();
    }

    private void refreshLutSlotStyles() {
        if (lutLabel != null) {
            lutLabel.setTextColor(activeLutSlot == 1 ? ACCENT : TEXT_DIM);
        }
        if (lutLabel2 != null) {
            lutLabel2.setTextColor(activeLutSlot == 2 ? ACCENT : TEXT_DIM);
        }
    }

    private void pickLut() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQ_PICK_LUT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        if (requestCode == REQ_PICK_PHOTO) {
            loadPhoto(uri);
        } else if (requestCode == REQ_PICK_LUT2) {
            loadLut(uri, 2);
        } else if (requestCode == REQ_PICK_LUT) {
            loadLut(uri, activeLutSlot);
        } else if (requestCode == REQ_VIDEO) {
            prepareVideo(uri);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // 从 LUT 详情页二次跳进来时会复用实例，这里得把新参数吃进去
        setIntent(intent);
        applyIncoming(intent);
        overrideMode = parseOverride(intent.getStringExtra(EXTRA_SOURCE_MODE));
        float initial = intent.getFloatExtra(EXTRA_INTENSITY, -1f);
        if (initial >= 0f) {
            intensityBar.setProgressSilently(Math.round(Math.min(1f, initial) * 100f));
        }
        applySourceMode();
    }

    /** 顶栏标题显示媒体格式，官方那儿就是 JPEG / MP4 这么写的 */
    private void setTopBarTitle(String text) {
        if (topBarTitle != null && text != null && !text.isEmpty()) {
            topBarTitle.setText(text);
        }
    }

    private static String mediaLabel(Uri uri) {
        String last = uri == null ? null : uri.getLastPathSegment();
        if (last == null) {
            return "JPEG";
        }
        String lower = last.toLowerCase(java.util.Locale.US);
        if (lower.endsWith(".png")) {
            return "PNG";
        }
        if (lower.endsWith(".webp")) {
            return "WEBP";
        }
        if (lower.endsWith(".heic") || lower.endsWith(".heif")) {
            return "HEIF";
        }
        if (lower.endsWith(".mp4")) {
            return "MP4";
        }
        if (lower.endsWith(".mov")) {
            return "MOV";
        }
        return "JPEG";
    }

    private void loadPhoto(Uri uri) {
        Toast.makeText(this, R.string.edit_loading, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                // 先认素材，再解码 —— 认出来才知道要不要在套 LUT 前转 V-Log
                SourceProbe probe = SourceProbe.probe(this, uri);
                int maxDimension = Math.max(getResources().getDisplayMetrics().widthPixels,
                        getResources().getDisplayMetrics().heightPixels);
                Bitmap bitmap = SourceLoader.load(this, uri, maxDimension);
                Log.d("EditActivity", "源图载入完成 " + bitmap.getWidth() + "x" + bitmap.getHeight()
                        + " 素材=" + probe.label + " 判定=" + probe.kind);
                runOnUiThread(() -> {
                    if (currentBitmap != null && currentBitmap != bitmap) {
                        currentBitmap.recycle();
                    }
                    currentBitmap = bitmap;
                    currentUri = uri;
                    source = probe;
                    preview.setImage(bitmap);
                    setTopBarTitle(mediaLabel(uri));
                    applySourceMode();
                    applyCrop();
                    // 导入新照片 = 开新工程：旧的草稿对应的是上一张图，留着只会让人困惑。
                    // 撤销栈也一并清掉，否则一按撤销会退回到上一张照片的参数。
                    DraftStore.clear(this);
                    undoStack.clear();
                    updateUndoButtons();
                    // 缩略图跟着新照片走：先裁参考图，再把旧缓存丢掉重烘
                    rebuildReference();
                    LutLibrary.invalidateThumbs();
                    refreshLibrary();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        getString(R.string.edit_failed, String.valueOf(e.getMessage())),
                        Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void loadLut(Uri uri) {
        loadLut(uri, 1);
    }

    /** slot=1 第一条；slot=2 第二条（叠加在第一条之后，对应官方的 LUT 2） */
    private void loadLut(Uri uri, int slot) {
        new Thread(() -> {
            try (InputStream in = SourceLoader.open(this, uri)) {
                if (in == null) {
                    throw new IllegalStateException("打不开这个文件");
                }
                LutData data = CubeParser.parse(in, uri.getLastPathSegment());
                Log.d("EditActivity", "LUT 解析完成 " + data.size + "³ title=" + data.title
                        + " 目标格调=" + data.targetStyle);

                // 再落一份副本：视频转码走 ffmpeg 的 lut3d，它只认文件路径
                java.io.File lutFile = new java.io.File(getCacheDir(), "current.cube");
                try (InputStream copyIn = SourceLoader.open(this, uri);
                     OutputStream copyOut = new java.io.FileOutputStream(lutFile)) {
                    if (copyIn != null) {
                        byte[] buffer = new byte[1 << 16];
                        int read;
                        while ((read = copyIn.read(buffer)) > 0) {
                            copyOut.write(buffer, 0, read);
                        }
                        currentLutFile = lutFile;
                    }
                }

                runOnUiThread(() -> {
                    if (slot == 2) {
                        currentLut2 = data;
                        preview.setLut2(data);
                        if (lutLabel2 != null) {
                            lutLabel2.setText("LUT 2: " + data.title + "（叠加）");
                            refreshLutSlotStyles();
                        }
                    } else {
                        currentLut = data;
                        preview.setLut(data);
                        lutLabel.setText(formatLutLabel(data));
                        refreshLutSlotStyles();
                        // LUT 换了，它要的输入格调可能也变了，重新算一次转换模式
                        applySourceMode();
                    }
                });
            } catch (Exception e) {
                Log.e("EditActivity", "LUT 载入失败", e);
                runOnUiThread(() -> Toast.makeText(this,
                        getString(R.string.edit_failed, String.valueOf(e.getMessage())),
                        Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (currentBitmap != null) {
            currentBitmap.recycle();
            currentBitmap = null;
        }
    }
}
