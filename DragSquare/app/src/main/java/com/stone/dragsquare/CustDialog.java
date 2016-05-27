package com.stone.dragsquare;

import android.content.Context;
import android.os.Bundle;
import android.support.v7.app.AlertDialog;
import android.view.View;
import android.widget.Button;

/**
 * Created by Administrator on 2016/5/27.
 */
public class CustDialog extends AlertDialog {

    private Button button1, button2;
    private View.OnClickListener btnListener;
    private View.OnClickListener innerListener;

    protected CustDialog(Context context) {
        super(context);
    }

    public CustDialog(Context context, int theme) {
        super(context, theme);
    }

    public CustDialog(Context context, boolean cancelable, OnCancelListener cancelListener) {
        super(context, cancelable, cancelListener);
    }

    public void setClickListener(View.OnClickListener btnListener) {
        this.btnListener = btnListener;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.cust_dialog);

        innerListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                btnListener.onClick(v);
                dismiss();
            }
        };

        findViewById(R.id.pick_image).setOnClickListener(innerListener);
        findViewById(R.id.delete).setOnClickListener(innerListener);
    }
}
