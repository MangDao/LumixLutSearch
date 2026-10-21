package io.github.lumixlut.edit.video;

/**
 * 变速方案。
 *
 * 现在只有匀速，但接口按「任意时刻取任意倍率」设计——
 * 官方的 S&Q 曲线变速（整段 5x→1x→5x、拖点 1x~10x）以后直接实现这个接口就行，
 * 不用再改转码那层。
 */
public interface SpeedPlan {

    /** 整段视频的归一化位置（0..1）上要多少倍率，曲线变速时才用得上 */
    float speedAt(float progress);

    /** 整体倍率：算输出时长、以及音频 atempo 都用它 */
    float averageSpeed();

    /** 给界面看的名字，比如 "2x" */
    String label();
}
