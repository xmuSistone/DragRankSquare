package com.stone.dragsquare;

import android.content.Context;
import android.graphics.Point;
import android.os.Handler;
import android.os.Message;
import android.support.v4.view.GestureDetectorCompat;
import android.support.v4.widget.ViewDragHelper;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.List;

/**
 * 正方形的拖拽面板
 * Created by xmuSistone on 2016/5/23.
 */
public class DraggableSquareView extends ViewGroup {
    // ACTION_DOWN按下后超过这个时间，就直接touch拦截，不会调用底层view的onClick事件
    private static final int INTERCEPT_TIME_SLOP = 200;
    private final int[] allStatus = {DraggableItemView.STATUS_LEFT_TOP, DraggableItemView.STATUS_RIGHT_TOP,
            DraggableItemView.STATUS_RIGHT_MIDDLE, DraggableItemView.STATUS_RIGHT_BOTTOM,
            DraggableItemView.STATUS_MIDDLE_BOTTOM, DraggableItemView.STATUS_LEFT_BOTTOM};

    private int mTouchSlop = 5; // 判定为滑动的阈值，单位是像素
    private int spaceInterval = 4; // 小方块之间的间隔
    private final ViewDragHelper mDragHelper;
    private GestureDetectorCompat moveDetector;

    private List<Point> originViewPositionList = new ArrayList<>(); // 保存最初状态时每个itemView的坐标位置
    private DraggableItemView draggingView; // 正在拖拽的view

    private int sideLength; // 每一个小方块的边长
    private long downTime = 0; // 按下的时间
    private int downX, downY;  // 按下时的坐标位置
    private Thread moveAnchorThread; // 按下的时候，itemView的重心移动，此为对应线程
    private Handler anchorHandler; // itemView需要移动重心，此为对应的Handler
    private Object synObj = new Object();

    public DraggableSquareView(Context context) {
        this(context, null);
    }

    public DraggableSquareView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public DraggableSquareView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mDragHelper = ViewDragHelper
                .create(this, 10f, new DragHelperCallback());
        moveDetector = new GestureDetectorCompat(context,
                new MoveDetector());
        moveDetector.setIsLongpressEnabled(false); // 不能处理长按事件，否则违背最初设计的初衷
        spaceInterval = (int) getResources().getDimension(R.dimen.drag_square_interval); // 小方块之间的间隔

        // 滑动的距离阈值由系统提供
        ViewConfiguration configuration = ViewConfiguration.get(getContext());
        mTouchSlop = configuration.getScaledTouchSlop();

        anchorHandler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                if (draggingView != null) {
                    // 开始移动重心的动画
                    draggingView.startAnchorAnimation();
                }
            }
        };
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        int len = allStatus.length;
        for (int i = 0; i < len; i++) {
            // 渲染结束之后，朝viewGroup中添加子View
            DraggableItemView itemView = new DraggableItemView(getContext());
            itemView.setStatus(allStatus[i]);
            itemView.setParentView(this);
            originViewPositionList.add(new Point()); //  原始位置点，由此初始化，一定与子View的status绑定
            addView(itemView);
        }
    }

    public Point getOriginViewPos(int status) {
        return originViewPositionList.get(status);
    }

    /**
     * 给imageView添加图片
     */
    public void fillItemImage(int imageStatus, String imagePath, boolean isModify) {
        // 1. 如果是修改图片，直接填充就好
        if (isModify) {
            DraggableItemView itemView = getItemViewByStatus(imageStatus);
            itemView.fillImageView(imagePath);
            return;
        }

        // 2. 新增图片
        for (int i = 0; i < allStatus.length; i++) {
            DraggableItemView itemView = getItemViewByStatus(i);
            if (!itemView.isDraggable()) {
                itemView.fillImageView(imagePath);
                break;
            }
        }
    }

    /**
     * 删除某一个ImageView时，该imageView变成空的，需要移动到队尾
     */
    public void onDedeleteImage(DraggableItemView deleteView) {
        int status = deleteView.getStatus();
        int lastDraggableViewStatus = -1;
        // 顺次将可拖拽的view往前移
        for (int i = status + 1; i < allStatus.length; i++) {
            DraggableItemView itemView = getItemViewByStatus(i);
            if (itemView.isDraggable()) {
                // 可拖拽的view往前移
                lastDraggableViewStatus = i;
                switchPosition(i, i - 1);
            } else {
                break;
            }
        }
        if (lastDraggableViewStatus > 0) {
            // 被delete的view移动到队尾
            deleteView.switchPosition(lastDraggableViewStatus);
        }
    }

    /**
     * 这是viewdraghelper拖拽效果的主要逻辑
     */
    private class DragHelperCallback extends ViewDragHelper.Callback {

        @Override
        public void onViewPositionChanged(View changedView, int left, int top, int dx, int dy) {
            // draggingView拖动的时候，如果与其它子view交换位置，其他子view位置改变，也会进入这个回调
            // 所以此处加了一层判断，剔除不关心的回调，以优化性能
            if (changedView == draggingView) {
                DraggableItemView changedItemView = (DraggableItemView) changedView;
                switchPositionIfNeeded(changedItemView);
            }
        }

        @Override
        public boolean tryCaptureView(View child, int pointerId) {
            // 按下的时候，缩放到最小的级别
            draggingView = (DraggableItemView) child;
            return draggingView.isDraggable();
        }

        @Override
        public void onViewReleased(View releasedChild, float xvel, float yvel) {
            DraggableItemView itemView = (DraggableItemView) releasedChild;
            itemView.onDragRelease();
        }

        @Override
        public int clampViewPositionHorizontal(View child, int left, int dx) {
            DraggableItemView itemView = (DraggableItemView) child;
            return itemView.computeDraggingX(dx);
        }

        @Override
        public int clampViewPositionVertical(View child, int top, int dy) {
            DraggableItemView itemView = (DraggableItemView) child;
            return itemView.computeDraggingY(dy);
        }
    }

    /**
     * 根据draggingView的位置，看看是否需要与其它itemView互换位置
     */
    private void switchPositionIfNeeded(DraggableItemView draggingView) {
        int centerX = draggingView.getLeft() + sideLength / 2;
        int centerY = draggingView.getTop() + sideLength / 2;
        int everyWidth = getMeasuredWidth() / 3;

        int fromStatus = -1, toStatus = draggingView.getStatus();

        switch (draggingView.getStatus()) {
            case DraggableItemView.STATUS_LEFT_TOP:
                // 拖动的是左上角的大图
                // 依次将小图向上顶
                int fromChangeIndex = 0;
                if (centerX > everyWidth * 2) {
                    // 大图往右越过了位置，一定会跟右侧的三个View交换位置才行
                    if (centerY < everyWidth) {
                        // 跟右上角的View交换位置
                        fromChangeIndex = DraggableItemView.STATUS_RIGHT_TOP;
                    } else if (centerY < everyWidth * 2) {
                        fromChangeIndex = DraggableItemView.STATUS_RIGHT_MIDDLE;
                    } else {
                        fromChangeIndex = DraggableItemView.STATUS_RIGHT_BOTTOM;
                    }
                } else if (centerY > everyWidth * 2) {
                    if (centerX < everyWidth) {
                        fromChangeIndex = DraggableItemView.STATUS_LEFT_BOTTOM;
                    } else if (centerX < everyWidth * 2) {
                        fromChangeIndex = DraggableItemView.STATUS_MIDDLE_BOTTOM;
                    } else {
                        fromChangeIndex = DraggableItemView.STATUS_RIGHT_BOTTOM;
                    }
                }

                DraggableItemView toItemView = getItemViewByStatus(fromChangeIndex);
                if (!toItemView.isDraggable()) {
                    return;
                }

                synchronized (this) {
                    for (int i = 1; i <= fromChangeIndex; i++) {
                        switchPosition(i, i - 1);
                    }
                    draggingView.setStatus(fromChangeIndex);
                }
                return;
            case DraggableItemView.STATUS_RIGHT_TOP:
                if (centerX < everyWidth * 2) {
                    fromStatus = DraggableItemView.STATUS_LEFT_TOP;
                } else if (centerY > everyWidth) {
                    fromStatus = DraggableItemView.STATUS_RIGHT_MIDDLE;
                }
                break;

            case DraggableItemView.STATUS_RIGHT_MIDDLE:
                if (centerX < everyWidth * 2 && centerY < everyWidth * 2) {
                    fromStatus = DraggableItemView.STATUS_LEFT_TOP;
                } else if (centerY < everyWidth) {
                    fromStatus = DraggableItemView.STATUS_RIGHT_TOP;
                } else if (centerY > everyWidth * 2) {
                    fromStatus = DraggableItemView.STATUS_RIGHT_BOTTOM;
                }
                break;
            case DraggableItemView.STATUS_RIGHT_BOTTOM:
                if (centerX < everyWidth * 2 && centerY < everyWidth * 2) {
                    fromStatus = DraggableItemView.STATUS_LEFT_TOP;
                } else if (centerX < everyWidth * 2) {
                    fromStatus = DraggableItemView.STATUS_MIDDLE_BOTTOM;
                } else if (centerY < everyWidth * 2) {
                    fromStatus = DraggableItemView.STATUS_RIGHT_MIDDLE;
                }
                break;
            case DraggableItemView.STATUS_MIDDLE_BOTTOM:
                if (centerX < everyWidth) {
                    fromStatus = DraggableItemView.STATUS_LEFT_BOTTOM;
                } else if (centerX > everyWidth * 2) {
                    fromStatus = DraggableItemView.STATUS_RIGHT_BOTTOM;
                } else if (centerY < everyWidth * 2) {
                    fromStatus = DraggableItemView.STATUS_LEFT_TOP;
                }
                break;
            case DraggableItemView.STATUS_LEFT_BOTTOM:
                if (centerX > everyWidth) {
                    fromStatus = DraggableItemView.STATUS_MIDDLE_BOTTOM;
                } else if (centerY < everyWidth * 2) {
                    fromStatus = DraggableItemView.STATUS_LEFT_TOP;
                }
                break;
            default:
                break;
        }

        synchronized (synObj) {
            if (fromStatus > 0) {
                if (switchPosition(fromStatus, toStatus)) {
                    draggingView.setStatus(fromStatus);
                }
            } else if (fromStatus == 0) {
                for (int i = toStatus - 1; i >= 0; i--) {
                    switchPosition(i, i + 1);
                }
                draggingView.setStatus(fromStatus);
            }
        }
    }

    /**
     * 调换位置
     */
    private boolean switchPosition(int fromStatus, int toStatus) {
        DraggableItemView itemView = getItemViewByStatus(fromStatus);
        if (itemView.isDraggable()) {
            itemView.switchPosition(toStatus);
            return true;
        }
        return false;
    }

    /**
     * 根据status获取itemView
     */
    private DraggableItemView getItemViewByStatus(int status) {
        int num = getChildCount();
        for (int i = 0; i < num; i++) {
            DraggableItemView itemView = (DraggableItemView) getChildAt(i);
            if (itemView.getStatus() == status) {
                return itemView;
            }
        }
        return null;
    }

    class MoveDetector extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx,
                                float dy) {
            // 拖动了，touch不往下传递
            return Math.abs(dy) + Math.abs(dx) > mTouchSlop;
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int everyLength = (getMeasuredWidth() - 4 * spaceInterval) / 3;
        int itemLeft = 0;
        int itemTop = 0;
        int itemRight = 0;
        int itemBottom = 0;
        // 每个view的边长是everyLength * 2 + spaceInterval
        sideLength = everyLength * 2 + spaceInterval;
        int halfSideLength = sideLength / 2; // 边长的一半
        int rightCenter = r - spaceInterval - everyLength / 2;
        int bottomCenter = b - spaceInterval - everyLength / 2;

        float scaleRate = (float) everyLength / sideLength;
        int num = getChildCount();
        for (int i = 0; i < num; i++) {
            DraggableItemView itemView = (DraggableItemView) getChildAt(i);
            itemView.setScaleRate(scaleRate);
            switch (itemView.getStatus()) {
                case DraggableItemView.STATUS_LEFT_TOP:
                    int centerPos = spaceInterval + everyLength + spaceInterval / 2;
                    itemLeft = centerPos - halfSideLength;
                    itemRight = centerPos + halfSideLength;
                    itemTop = centerPos - halfSideLength;
                    itemBottom = centerPos + halfSideLength;
                    break;
                case DraggableItemView.STATUS_RIGHT_TOP:
                    itemLeft = rightCenter - halfSideLength;
                    itemRight = rightCenter + halfSideLength;
                    int hCenter1 = spaceInterval + everyLength / 2;
                    itemTop = hCenter1 - halfSideLength;
                    itemBottom = hCenter1 + halfSideLength;
                    break;
                case DraggableItemView.STATUS_RIGHT_MIDDLE:
                    itemLeft = rightCenter - halfSideLength;
                    itemRight = rightCenter + halfSideLength;
                    int hCenter2 = t + getMeasuredHeight() / 2;
                    itemTop = hCenter2 - halfSideLength;
                    itemBottom = hCenter2 + halfSideLength;
                    break;
                case DraggableItemView.STATUS_RIGHT_BOTTOM:
                    itemLeft = rightCenter - halfSideLength;
                    itemRight = rightCenter + halfSideLength;
                    itemTop = bottomCenter - halfSideLength;
                    itemBottom = bottomCenter + halfSideLength;
                    break;
                case DraggableItemView.STATUS_MIDDLE_BOTTOM:
                    int vCenter1 = l + getMeasuredWidth() / 2;
                    itemLeft = vCenter1 - halfSideLength;
                    itemRight = vCenter1 + halfSideLength;
                    itemTop = bottomCenter - halfSideLength;
                    itemBottom = bottomCenter + halfSideLength;
                    break;
                case DraggableItemView.STATUS_LEFT_BOTTOM:
                    int vCenter2 = l + spaceInterval + everyLength / 2;
                    itemLeft = vCenter2 - halfSideLength;
                    itemRight = vCenter2 + halfSideLength;
                    itemTop = bottomCenter - halfSideLength;
                    itemBottom = bottomCenter + halfSideLength;
                    break;
            }

            ViewGroup.LayoutParams lp = itemView.getLayoutParams();
            lp.width = sideLength;
            lp.height = sideLength;
            itemView.setLayoutParams(lp);

            Point itemPoint = originViewPositionList.get(itemView.getStatus());
            itemPoint.x = itemLeft;
            itemPoint.y = itemTop;

            itemView.layout(itemLeft, itemTop, itemRight, itemBottom);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        measureChildren(widthMeasureSpec, widthMeasureSpec);
        int maxWidth = MeasureSpec.getSize(widthMeasureSpec);
        int width = resolveSizeAndState(maxWidth, widthMeasureSpec, 0);
        setMeasuredDimension(width, width);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            // 手指按下的时候，需要把某些view bringToFront，否则的话，tryCapture将不按预期工作
            getParent().requestDisallowInterceptTouchEvent(true);
            downX = (int) ev.getX();
            downY = (int) ev.getY();
            downTime = System.currentTimeMillis();
            bringToFrontWhenTouchDown(downX, downY);
        } else if (ev.getAction() == MotionEvent.ACTION_UP) {
            if (draggingView != null) {
                draggingView.onDragRelease();
            }
            draggingView = null;

            if (null != moveAnchorThread) {
                moveAnchorThread.interrupt();
                moveAnchorThread = null;
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    /**
     * 按下时根据触点的位置，将某个view bring到前台
     */
    private void bringToFrontWhenTouchDown(final int downX, final int downY) {
        int statusIndex = getStatusByDownPoint(downX, downY);
        final DraggableItemView itemView = getItemViewByStatus(statusIndex);
        if (indexOfChild(itemView) != getChildCount() - 1) {
            bringChildToFront(itemView);
        }
        if (!itemView.isDraggable()) {
            return;
        }

        itemView.saveAnchorInfo(downX, downY);
        moveAnchorThread = new Thread() {
            @Override
            public void run() {
                try {
                    sleep(INTERCEPT_TIME_SLOP);
                } catch (InterruptedException e) {
                }

                Message msg = anchorHandler.obtainMessage();
                msg.sendToTarget();
            }
        };
        moveAnchorThread.start();
    }

    private int getStatusByDownPoint(int downX, int downY) {
        int everyWidth = getMeasuredWidth() / 3;
        if (downX < everyWidth) {
            if (downY < everyWidth * 2) {
                return DraggableItemView.STATUS_LEFT_TOP;
            } else {
                return DraggableItemView.STATUS_LEFT_BOTTOM;
            }
        } else if (downX < everyWidth * 2) {
            if (downY < everyWidth * 2) {
                return DraggableItemView.STATUS_LEFT_TOP;
            } else {
                return DraggableItemView.STATUS_MIDDLE_BOTTOM;
            }
        } else {
            if (downY < everyWidth) {
                return DraggableItemView.STATUS_RIGHT_TOP;
            } else if (downY < everyWidth * 2) {
                return DraggableItemView.STATUS_RIGHT_MIDDLE;
            } else {
                return DraggableItemView.STATUS_RIGHT_BOTTOM;
            }
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (downTime > 0 && System.currentTimeMillis() - downTime > INTERCEPT_TIME_SLOP) {
            return true;
        }
        boolean shouldIntercept = mDragHelper.shouldInterceptTouchEvent(ev);
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            mDragHelper.processTouchEvent(ev);
        }

        boolean moveFlag = moveDetector.onTouchEvent(ev);
        if (moveFlag) {
            if (null != moveAnchorThread) {
                moveAnchorThread.interrupt();
                moveAnchorThread = null;
            }

            if (null != draggingView && draggingView.isDraggable()) {
                draggingView.startAnchorAnimation();
            }
        }
        return shouldIntercept && moveFlag;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        try {
            // 该行代码可能会抛异常，正式发布时请将这行代码加上try catch
            mDragHelper.processTouchEvent(e);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return true;
    }
}