package com.crudtool.listener;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.editor.event.EditorFactoryEvent;
import com.intellij.openapi.editor.event.EditorFactoryListener;
import com.intellij.openapi.editor.event.EditorMouseEvent;
import com.intellij.openapi.editor.event.EditorMouseListener;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.crudtool.action.MapperXmlJumpAction;
import org.jetbrains.annotations.NotNull;

import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * @Description: 编辑器级 Ctrl+Alt+Click 拦截器
 *
 * 方案：通过 EditorFactoryListener 在每个编辑器上注册 EditorMouseListener，
 * 在 IDEA 处理快捷键之前拦截 mousePressed 事件：
 *   - 命中 Mapper 元素且找到目标：执行跳转并消费事件
 *   - 命中 Mapper 元素但未找到目标：消费事件，阻止 GotoImplementation 弹出 No implementations
 *   - 非 Mapper 元素：不消费事件，GotoImplementation 正常执行
 *
 * 关键：
 * 1. 鼠标事件回调中访问 PSI/索引必须持有读锁，且导航（navigate）需要写锁，
 *    必须在读锁释放后执行。
 * 2. mousePressed 触发时光标尚未移动到鼠标点击位置，必须通过鼠标坐标计算真实 offset，
 *    而非使用 editor.getCaretModel().getOffset()（那是旧光标位置）。
 */
public class MapperJumpEditorListener implements EditorFactoryListener {

    private final Map<Editor, EditorMouseListener> listeners = new WeakHashMap<>();

    @Override
    public void editorCreated(@NotNull EditorFactoryEvent event) {
        Editor editor = event.getEditor();
        EditorMouseListener listener = new EditorMouseListener() {
            @Override
            public void mousePressed(@NotNull EditorMouseEvent e) {
                MouseEvent mouseEvent = e.getMouseEvent();
                if (mouseEvent.getButton() != MouseEvent.BUTTON1) return;
                int mods = mouseEvent.getModifiersEx();
                boolean ctrl = (mods & InputEvent.CTRL_DOWN_MASK) != 0;
                boolean alt = (mods & InputEvent.ALT_DOWN_MASK) != 0;
                if (!ctrl || !alt) return;
                if (mouseEvent.isPopupTrigger()) return;

                Editor editor = e.getEditor();
                Project project = editor.getProject();
                if (project == null) return;

                // 关键：根据鼠标点击坐标计算文档偏移量
                // mousePressed 时光标(caret)尚未移动到点击位置，不能用 caretModel.getOffset()
                Point mousePoint = mouseEvent.getPoint();
                LogicalPosition logicalPos = editor.xyToLogicalPosition(mousePoint);
                int offset = editor.logicalPositionToOffset(logicalPos);

                // 1. 读锁内获取 PsiFile（PSI 访问必须持有读锁）
                PsiFile psiFile = ReadAction.compute(() ->
                        PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument())
                );
                if (psiFile == null) return;

                // 2. 读锁内查找跳转目标（使用鼠标点击处的 offset，而非旧的 caret 位置）
                int finalOffset = offset;
                MapperXmlJumpAction.JumpResult result = ReadAction.compute(() ->
                        MapperXmlJumpAction.findJumpTarget(project, editor, psiFile, finalOffset)
                );

                // 3. 读锁外处理结果：导航（需写锁）
                //    shouldConsume()=true 表示是 Mapper 元素，消费事件阻止 GotoImplementation 弹窗
                if (result.shouldConsume()) {
                    e.consume();
                    MapperXmlJumpAction.handleResult(project, result);
                }
            }

            @Override public void mouseReleased(@NotNull EditorMouseEvent e) {}
            @Override public void mouseClicked(@NotNull EditorMouseEvent e) {}
            @Override public void mouseEntered(@NotNull EditorMouseEvent e) {}
            @Override public void mouseExited(@NotNull EditorMouseEvent e) {}
        };
        editor.addEditorMouseListener(listener);
        synchronized (listeners) {
            listeners.put(editor, listener);
        }
    }

    @Override
    public void editorReleased(@NotNull EditorFactoryEvent event) {
        Editor editor = event.getEditor();
        EditorMouseListener listener;
        synchronized (listeners) {
            listener = listeners.remove(editor);
        }
        if (listener != null) {
            editor.removeEditorMouseListener(listener);
        }
    }
}
