package com.example.volumetriclight.client;

import com.example.volumetriclight.Light;
import com.example.volumetriclight.LightNetwork;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.Predicate;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

public final class LightEditor extends Screen {
    private static final int AMBER = 0xffedbd68;
    private Light draft;
    private CompoundTag base;
    private int tab, listOffset, scroll, contentHeight, leftWidth, panelX, panelWidth, formTop, formBottom;
    private boolean dirty, fresh, waiting;
    private String status = "修改仅在本地预览；应用后保存";
    private final List<Row> rows = new ArrayList<>();
    private final List<AbstractWidget> formWidgets = new ArrayList<>();
    private final Map<EditBox, Predicate<String>> validators = new LinkedHashMap<>();
    private Button apply;
    private Vec3 oldA, oldB;
    public boolean guides = true;
    public float viewYaw, viewPitch;
    public double viewFov = 70;
    private record Row(String label, int offset, List<AbstractWidget> widgets) {}

    public LightEditor() { super(Component.literal("体积光编辑器")); }
    @Override protected void init() {
        if (draft == null) {
            if (!LightClient.lights.isEmpty()) load(LightClient.lights.values().iterator().next().light());
            else newDraft();
        }
        leftWidth = width >= 560 ? 112 : 0;
        panelWidth = Math.min(264, Math.max(180, width / 3)); panelX = width - panelWidth - 6;
        formTop = 60; formBottom = height - 46;
        clearWidgets();
        if (leftWidth > 0) {
            button(7, 30, leftWidth - 12, "＋ 新建灯光", b -> discardThen(this::createDraft));
            int y = 57, index = 0;
            for (var entry : LightClient.lights.values()) {
                if (index++ < listOffset) continue;
                if (y + 20 > height - 44) break;
                Light l = entry.light(); String label = (draft.id.equals(l.id) ? "› " : "") + l.name;
                button(7, y, leftWidth - 12, font.plainSubstrByWidth(label, leftWidth - 20), b -> discardThen(() -> { load(l); init(); })); y += 24;
            }
        } else {
            button(6, 29, 58, "选择灯光", b -> chooseLight());
            button(68, 29, 44, "新建", b -> discardThen(this::createDraft));
        }
        String[] tabs = {"光束", "追踪", "触发"};
        int tabWidth = panelWidth / 3;
        for (int i = 0; i < 3; i++) {
            int selected = i;
            Button b = button(panelX + i * tabWidth, 30, tabWidth - 2, tabs[i], x -> { tab = selected; scroll = 0; init(); });
            if (tab == i) b.setFGColor(AMBER);
        }
        int viewWidth = panelX - leftWidth - 12;
        int toolbarX = leftWidth > 0 ? leftWidth + 8 : 116;
        if (panelX - toolbarX > 135) {
            button(toolbarX, 30, 62, "重置视角", b -> resetView());
            button(toolbarX + 66, 30, 58, "辅助线", b -> guides = !guides);
        }
        int y = height - 24;
        apply = button(width - 56, y, 50, "应用", b -> apply());
        button(width - 105, y, 44, "取消", b -> { LightClient.preview = null; minecraft.setScreen(null); });
        button(width - 174, y, 64, "复制指令", b -> {
            if (!valid()) return;
            minecraft.keyboardHandler.setClipboard(draft.exportCommand()); status = "已复制完整配置指令，可用于命令方块";
        });
        button(width - 223, y, 44, "重载", b -> discardThen(() -> {
            var current = LightClient.lights.get(draft.id);
            if (current != null) load(current.light()); else newDraft(); init();
        }));
        button(width - 272, y, 44, "删除", b -> {
            if (fresh) { newDraft(); init(); return; }
            minecraft.setScreen(new ConfirmScreen(answer -> {
                minecraft.setScreen(this);
                if (answer) { waiting = true; LightNetwork.send(LightNetwork.Operation.DELETE, draft.write()); }
            }, Component.literal("删除这盏灯？"), Component.literal(draft.name)));
        });
        buildForm(); LightClient.preview = draft;
    }
    private void load(Light light) {
        draft = light.copy(); base = draft.write(); fresh = false; dirty = false; scroll = 0;
        resetView(); LightClient.preview = draft;
    }
    private void createDraft() { newDraft(); init(); }
    private void newDraft() {
        int i = 1; while (LightClient.lights.containsKey("light_" + i)) i++;
        draft = new Light("light_" + i); draft.name = "新建灯光 " + i;
        if (minecraft != null && minecraft.player != null && minecraft.level != null) {
            Vec3 p = minecraft.player.position();
            Vec3 forward = minecraft.player.getLookAngle().multiply(1, 0, 1).normalize();
            if (forward.lengthSqr() < .5) forward = new Vec3(0, 0, 1);
            draft.source = p.add(forward.scale(4)).add(0, 5, 0); draft.target = p.add(forward.scale(9));
            draft.cornerA = draft.target.add(-2, -.5, -2); draft.cornerB = draft.target.add(2, 3, 2);
            draft.dimension = minecraft.level.dimension().location().toString();
        }
        base = null; fresh = true; dirty = true; scroll = 0; resetView(); LightClient.preview = draft;
    }
    private void resetView() {
        if (minecraft == null || minecraft.player == null || draft == null) return;
        Vec3 direction = draft.source.lerp(draft.target, .6).subtract(minecraft.player.getEyePosition());
        viewYaw = (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
        viewPitch = (float) -Math.toDegrees(Math.atan2(direction.y, Math.sqrt(direction.x * direction.x + direction.z * direction.z)));
        viewFov = 70;
    }
    public float cameraYaw() {
        double ndc = ((leftWidth + panelX) * .5 - width * .5) / (width * .5);
        return viewYaw + (float) Math.toDegrees(Math.atan(-ndc * Math.tan(Math.toRadians(viewFov * .5)) * width / height));
    }
    private Button button(int x, int y, int w, String label, Button.OnPress action) {
        return addRenderableWidget(Button.builder(Component.literal(label), action).bounds(x, y, w, 20).build());
    }
    private void changed() {
        dirty = base == null || !draft.write().equals(base); LightClient.preview = draft;
        status = dirty ? "未应用的修改仅在本地预览" : "已与服务器保存的配置一致";
    }
    private void buildForm() {
        rows.clear(); formWidgets.clear(); validators.clear(); contentHeight = 0;
        if (tab == 0) {
            text("灯光名称", draft.name, 48, s -> { draft.name = s; changed(); });
            cycle("灯光开关", draft.enabled ? "启用" : "关闭", () -> draft.enabled = !draft.enabled);
            coordinates("光源位置", draft.source, v -> draft.source = v);
            action("在世界中选取光源", () -> LightClient.beginPick(this, 0));
            coordinates("照射位置", draft.target, v -> draft.target = v);
            action("在世界中选取照射点", () -> LightClient.beginPick(this, 1));
            slider("出光口半径（格）", draft.radius, 0, 4, v -> draft.radius = (float) v);
            slider("光束张角（度）", draft.angle, 2, 100, v -> draft.angle = (float) v);
            slider("射程（格）", draft.range, 1, 64, v -> draft.range = (float) v);
            slider("亮度", draft.brightness, 0, 4, v -> draft.brightness = (float) v);
            slider("边缘柔和度", draft.softness, .01, 1, v -> draft.softness = (float) v);
            EditBox color = text("颜色（#RRGGBB）", draft.colorHex(), 7, s -> {
                if (s.matches("#?[0-9a-fA-F]{6}")) { draft.color = Integer.parseInt(s.replace("#", ""), 16); changed(); }
            });
            validators.put(color, s -> s.matches("#?[0-9a-fA-F]{6}"));
        } else if (tab == 1) {
            String[] modes = {"固定照射点", "指定玩家", "触发区域的玩家"};
            cycle("照射方式", modes[draft.tracking.ordinal()], () -> draft.tracking = Light.Tracking.values()[(draft.tracking.ordinal() + 1) % 3]);
            if (draft.tracking == Light.Tracking.PLAYER) {
                text("玩家名称（首次绑定须在线）", draft.playerName, 16, s -> { draft.playerName = s; changed(); });
                action("选择在线玩家", () -> choosePlayer(s -> draft.playerName = s));
            }
            slider("转向平滑度", draft.smoothing, .02, 1, v -> draft.smoothing = (float) v);
            note("瞄准玩家身体中心；目标离线时停灯");
            note("触发玩家模式：锁定最先进入者");
            action("测试追踪（本地模拟 5 秒）", () -> { LightClient.testTracking = true; LightClient.testTicks = 100; status = "测试中：模拟目标移动，仅自己可见"; });
        } else {
            String[] modes = {"关闭区域触发", "进入时触发一次", "区域有人时保持开启"};
            cycle("触发模式", modes[draft.trigger.ordinal()], () -> draft.trigger = Light.Trigger.values()[(draft.trigger.ordinal() + 1) % 3]);
            coordinates("区域角点 A", draft.cornerA, v -> draft.cornerA = v);
            coordinates("区域角点 B", draft.cornerB, v -> draft.cornerB = v);
            action("在世界中选择区域（两个角点）", () -> {
                oldA = draft.cornerA; oldB = draft.cornerB; LightClient.beginPick(this, 2);
            });
            cycle("触发玩家", draft.filterName.isEmpty() ? "任意玩家" : "指定：" + draft.filterName,
                () -> { if (!draft.filterName.isEmpty()) draft.filterName = ""; else draft.filterName = minecraft.player.getGameProfile().getName(); });
            if (!draft.filterName.isEmpty()) {
                text("指定玩家名称", draft.filterName, 16, s -> { draft.filterName = s; changed(); });
                action("选择在线玩家", () -> choosePlayer(s -> draft.filterName = s));
            }
            if (draft.trigger == Light.Trigger.ENTER) {
                String[] actions = {"开启（保持）", "关闭（保持）", "亮灯一段时间"};
                cycle("进入后执行", actions[draft.action.ordinal()], () -> draft.action = Light.Action.values()[(draft.action.ordinal() + 1) % 3]);
                number("持续时间（秒）", draft.duration / 20.0, .05, 3600, v -> draft.duration = (int) Math.round(v * 20));
                number("冷却时间（秒）", draft.cooldown / 20.0, 0, 3600, v -> draft.cooldown = (int) Math.round(v * 20));
            }
            note("区域线框仅用于编辑，不会出现在成品中");
            action("模拟进入（仅本地）", () -> { LightClient.testTrigger(); status = "测试中：模拟进入、停留和离开，仅自己可见"; });
        }
        layoutRows();
    }
    private Row row(String label, int height) {
        Row row = new Row(label, contentHeight, new ArrayList<>()); rows.add(row); contentHeight += height; return row;
    }
    private <T extends AbstractWidget> T widget(Row row, T widget) {
        row.widgets.add(widget); formWidgets.add(widget); return addRenderableWidget(widget);
    }
    private EditBox box(int x, int w, String label) {
        EditBox box = new EditBox(font, x, 0, w, 18, Component.literal(label)); box.setMaxLength(32); return box;
    }
    private EditBox text(String label, String initial, int max, Consumer<String> change) {
        Row row = row(label, 38); EditBox box = widget(row, box(panelX + 4, panelWidth - 10, label));
        box.setMaxLength(max); box.setValue(initial); box.setResponder(change); return box;
    }
    private void number(String label, double initial, double min, double max, DoubleConsumer setter) {
        EditBox box = text(label, format(initial), 24, s -> {
            try { double v = Double.parseDouble(s); if (Double.isFinite(v) && v >= min && v <= max) { setter.accept(v); changed(); } }
            catch (NumberFormatException ignored) { }
        });
        validators.put(box, s -> finite(s, min, max));
    }
    private static boolean finite(String s, double min, double max) {
        try { double v = Double.parseDouble(s); return Double.isFinite(v) && v >= min && v <= max; }
        catch (NumberFormatException e) { return false; }
    }
    private void coordinates(String label, Vec3 initial, Consumer<Vec3> setter) {
        Row row = row(label + "（X / Y / Z）", 38); int w = (panelWidth - 16) / 3;
        EditBox[] fields = new EditBox[3]; double[] values = {initial.x, initial.y, initial.z};
        for (int i = 0; i < 3; i++) {
            fields[i] = widget(row, box(panelX + 4 + i * (w + 3), w, label + " " + "XYZ".charAt(i)));
            fields[i].setValue(format(values[i])); validators.put(fields[i], s -> finite(s, -30000000, 30000000));
        }
        for (EditBox field : fields) field.setResponder(s -> {
            try {
                Vec3 v = new Vec3(Double.parseDouble(fields[0].getValue()), Double.parseDouble(fields[1].getValue()), Double.parseDouble(fields[2].getValue()));
                if (Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z)) { setter.accept(v); changed(); }
            } catch (NumberFormatException ignored) { }
        });
    }
    private void slider(String label, double initial, double min, double max, DoubleConsumer setter) {
        Row row = row(label, 38);
        EditBox numeric = widget(row, box(panelX + panelWidth - 62, 56, label)); numeric.setValue(format(initial));
        ValueSlider slider = widget(row, new ValueSlider(panelX + 4, panelWidth - 72, label, initial, min, max, v -> {
            setter.accept(v); numeric.setValue(format(v)); changed();
        }));
        numeric.setResponder(s -> {
            if (finite(s, min, max)) { double v = Double.parseDouble(s); setter.accept(v); slider.sync(v); changed(); }
        });
        validators.put(numeric, s -> finite(s, min, max));
    }
    private final class ValueSlider extends AbstractSliderButton {
        private final double min, max; private final DoubleConsumer setter;
        ValueSlider(int x, int w, String label, double v, double min, double max, DoubleConsumer setter) {
            super(x, 0, w, 18, Component.literal(label), (v - min) / (max - min)); this.min = min; this.max = max; this.setter = setter; updateMessage();
        }
        void sync(double v) { value = Mth.clamp((v - min) / (max - min), 0, 1); updateMessage(); }
        @Override protected void updateMessage() { setMessage(Component.literal(format(min + value * (max - min)))); }
        @Override protected void applyValue() { setter.accept(min + value * (max - min)); }
    }
    private void cycle(String label, String value, Runnable action) {
        Row row = row(label, 38);
        widget(row, Button.builder(Component.literal(value + " ▸"), b -> { action.run(); changed(); init(); }).bounds(panelX + 4, 0, panelWidth - 10, 19).build());
    }
    private void action(String label, Runnable action) {
        Row row = row("", 25);
        widget(row, Button.builder(Component.literal(label), b -> action.run()).bounds(panelX + 4, 0, panelWidth - 10, 20).build());
    }
    private void note(String text) { row(text, 17); }
    private void layoutRows() {
        scroll = Mth.clamp(scroll, 0, Math.max(0, contentHeight - (formBottom - formTop)));
        for (Row row : rows) for (AbstractWidget widget : row.widgets) {
            widget.setY(formTop + row.offset - scroll + (row.label.isEmpty() ? 0 : 12));
            widget.visible = widget.getY() >= formTop && widget.getY() + widget.getHeight() <= formBottom;
        }
    }
    private boolean valid() {
        for (var e : validators.entrySet()) if (!e.getValue().test(e.getKey().getValue())) {
            e.getKey().setTextColor(0xffff7777); status = "输入不正确，请检查数值范围和颜色格式"; return false;
        }
        try { draft.validate(); return true; } catch (IllegalArgumentException e) { status = e.getMessage(); return false; }
    }
    private void apply() {
        if (waiting || !valid()) return;
        waiting = true; status = "正在保存…";
        LightNetwork.send(fresh ? LightNetwork.Operation.CREATE : LightNetwork.Operation.SAVE, draft.write());
    }
    public void received(LightNetwork.Snapshot state) {
        if (!state.message().isEmpty()) { status = state.message(); waiting = false; }
        if (state.success() && !state.selected().isEmpty()) {
            var current = LightClient.lights.get(state.selected());
            if (current != null) load(current.light());
            else if (draft != null && state.selected().equals(draft.id)) {
                if (LightClient.lights.isEmpty()) newDraft(); else load(LightClient.lights.values().iterator().next().light());
            }
            init();
        } else if (!dirty && draft != null && LightClient.lights.containsKey(draft.id)
                && draft.revision != LightClient.lights.get(draft.id).light().revision) { load(LightClient.lights.get(draft.id).light()); init(); }
    }
    public void picked(int mode, Vec3 point) {
        switch (mode) { case 0 -> draft.source = point; case 1 -> { draft.target = point; draft.tracking = Light.Tracking.FIXED; }
            case 2 -> draft.cornerA = point; case 3 -> { draft.cornerB = point; if (draft.trigger == Light.Trigger.OFF) draft.trigger = Light.Trigger.ENTER; } }
        changed();
    }
    public void cancelRegionPick() { if (oldA != null) { draft.cornerA = oldA; draft.cornerB = oldB; changed(); } }
    private void discardThen(Runnable action) {
        if (!dirty) { action.run(); return; }
        minecraft.setScreen(new ConfirmScreen(answer -> { minecraft.setScreen(this); if (answer) action.run(); },
                Component.literal("放弃未应用的修改？"), Component.literal("已保存的灯光不会受影响")));
    }
    private void choosePlayer(Consumer<String> setter) {
        minecraft.setScreen(new ChoiceScreen(this, "选择在线玩家", List.copyOf(LightClient.players), s -> { setter.accept(s); changed(); }));
    }
    private void chooseLight() {
        discardThen(() -> minecraft.setScreen(new ChoiceScreen(this, "选择灯光", new ArrayList<>(LightClient.lights.keySet()), s -> load(LightClient.lights.get(s).light()))));
    }
    @Override public void tick() { for (AbstractWidget widget : formWidgets) if (widget instanceof EditBox box) box.tick(); }
    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        g.fill(0, 0, width, 25, 0xf0212428);
        g.drawString(font, "体积光编辑器", 8, 8, AMBER);
        String state = draft == null ? "" : draft.id + (fresh ? " · 新建草稿" : dirty ? " · 未应用" : " · 已保存");
        g.drawString(font, font.plainSubstrByWidth(state, width / 2), width / 2, 8, 0xffc7c7c7);
        if (leftWidth > 0) g.fill(0, 25, leftWidth, height - 29, 0xe6212428);
        g.fill(panelX - 3, 25, width, height - 29, 0xf0212428);
        g.enableScissor(panelX, formTop, width, formBottom);
        for (Row row : rows) if (!row.label.isEmpty())
            g.drawString(font, font.plainSubstrByWidth(row.label, panelWidth - 8), panelX + 4, formTop + row.offset - scroll, 0xffcccccc);
        g.disableScissor();
        if (contentHeight > formBottom - formTop) {
            int track = formBottom - formTop;
            int thumb = Math.max(12, track * track / contentHeight);
            int top = formTop + (track - thumb) * scroll / Math.max(1, contentHeight - track);
            g.fill(width - 3, formTop, width - 1, formBottom, 0xff111315);
            g.fill(width - 3, top, width - 1, top + thumb, AMBER);
        }
        g.fill(0, height - 42, width, height, 0xf0212428);
        g.drawString(font, font.plainSubstrByWidth(status, width - 12), 6, height - 38, 0xffe2ceaa);
        int viewportWidth = panelX - leftWidth - 12;
        if (viewportWidth > 80) {
            String hint = LightClient.testTicks > 0 ? "本地测试中" : "实时预览 · 仅自己可见";
            g.drawString(font, font.plainSubstrByWidth(hint, viewportWidth), leftWidth + 8, 58, AMBER);
            g.drawString(font, font.plainSubstrByWidth("拖动观察 · 滚轮缩放", viewportWidth), leftWidth + 8, height - 56, 0xffeeeeee);
        }
        apply.active = !waiting;
        super.render(g, mouseX, mouseY, partial);
    }
    @Override public boolean mouseScrolled(double x, double y, double delta) {
        if (x >= panelX) { scroll -= (int) (delta * 28); layoutRows(); return true; }
        if (x < leftWidth) { listOffset = Mth.clamp(listOffset - (int) delta, 0, Math.max(0, LightClient.lights.size() - 1)); init(); return true; }
        viewFov = Mth.clamp(viewFov - delta * 4, 25, 110); return true;
    }
    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (button == 0 && x > leftWidth && x < panelX - 4 && y > 52 && y < height - 45) {
            viewYaw += (float) dx * .4f; viewPitch = Mth.clamp(viewPitch + (float) dy * .4f, -89, 89); return true;
        }
        return super.mouseDragged(x, y, button, dx, dy);
    }
    @Override public void onClose() { discardThen(() -> minecraft.setScreen(null)); }
    @Override public void removed() { LightClient.preview = null; LightClient.testTicks = 0; }
    @Override public boolean isPauseScreen() { return false; }
    private static String format(double v) { return String.format(Locale.ROOT, "%.3f", v).replaceAll("0+$", "").replaceAll("\\.$", ""); }

    private static final class ChoiceScreen extends Screen {
        private final LightEditor parent; private final List<String> choices; private final Consumer<String> select;
        private int page;
        ChoiceScreen(LightEditor parent, String title, List<String> choices, Consumer<String> select) {
            super(Component.literal(title)); this.parent = parent; this.choices = choices; this.select = select;
        }
        @Override protected void init() {
            clearWidgets(); int perPage = Math.max(1, (height - 100) / 24);
            for (int i = page * perPage; i < Math.min(choices.size(), (page + 1) * perPage); i++) {
                String choice = choices.get(i);
                addRenderableWidget(Button.builder(Component.literal(choice), b -> { select.accept(choice); minecraft.setScreen(parent); })
                    .bounds(width / 2 - 100, 35 + (i - page * perPage) * 24, 200, 20).build());
            }
            addRenderableWidget(Button.builder(Component.literal("上一页"), b -> { if (page > 0) { page--; init(); } }).bounds(width / 2 - 100, height - 51, 96, 20).build());
            addRenderableWidget(Button.builder(Component.literal("下一页"), b -> { if ((page + 1) * perPage < choices.size()) { page++; init(); } }).bounds(width / 2 + 4, height - 51, 96, 20).build());
            addRenderableWidget(Button.builder(Component.literal("返回"), b -> onClose()).bounds(width / 2 - 100, height - 27, 200, 20).build());
        }
        @Override public void render(GuiGraphics g, int x, int y, float partial) { renderBackground(g); g.drawCenteredString(font, title, width / 2, 14, AMBER); super.render(g, x, y, partial); }
        @Override public void onClose() { minecraft.setScreen(parent); }
        @Override public boolean isPauseScreen() { return false; }
    }
}
