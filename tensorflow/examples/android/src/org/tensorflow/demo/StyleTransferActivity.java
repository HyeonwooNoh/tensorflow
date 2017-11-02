package org.tensorflow.demo;

import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.app.Activity;
import android.util.TypedValue;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.Toast;

import org.tensorflow.contrib.android.TensorFlowInferenceInterface;
import org.tensorflow.demo.env.ImageUtils;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;

public class StyleTransferActivity extends Activity {

    private static final String MODEL_FILE = "file:///android_asset/stylize_quantized.pb";
    private static final String INPUT_NODE = "input";
    private static final String STYLE_NODE = "style_num";
    private static final String OUTPUT_NODE = "transformer/expand/conv3/conv/Sigmoid";

    private static final float TEXT_SIZE_DIP = 12;

    private static final int NUM_STYLES = 26;
    private static final int[] SIZES = {128, 192, 256, 384, 512, 720};

    private static final int RESULT_LOAD_IMAGE = 1;

    // Whether to actively manipulate non-selected sliders so that sum of activations always appears
    // to be 1.0. The actual style input tensor will be normalized to sum to 1.0 regardless.
    private static final boolean NORMALIZE_SLIDERS = true;

    private int desiredSizeIndex = -1;
    private int desiredSize = 256;
    private int initializedSize = 0;

    private int frameNum = 0;

    //private BorderedText borderedText;
    private Bitmap croppedBitmap;
    private Bitmap loadedBitmap;
    private Bitmap textureCopyBitmap;
    private Matrix frameToCropTransform;

    private boolean allZero = false;

    private ImageGridAdapter adapter;
    private GridView grid;

    private int lastOtherStyle = 1;
    private final float[] styleVals = new float[NUM_STYLES];
    private int[] intValues;
    private float[] floatValues;

    private TensorFlowInferenceInterface inferenceInterface;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_style_transfer);

        textureCopyBitmap = Bitmap.createBitmap(desiredSize, desiredSize, Bitmap.Config.ARGB_8888);

        final Button load_button = (Button) findViewById(R.id.load_button);
        load_button.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // Code here executes on main thread after user presses button
                Intent photoPickerIntent = new Intent(Intent.ACTION_PICK);
                photoPickerIntent.setType("image/*");
                startActivityForResult(photoPickerIntent, RESULT_LOAD_IMAGE);

            }
        });
    }
    @Override
    protected void onActivityResult(int reqCode, int resultCode, Intent data) {
        super.onActivityResult(reqCode, resultCode, data);
        if (resultCode == RESULT_OK) {
            try {

                final Uri imageUri = data.getData();

                final InputStream imageStream = getContentResolver().openInputStream(imageUri);

                loadedBitmap = BitmapFactory.decodeStream(imageStream);
                final int loaded_width = loadedBitmap.getWidth();
                final int loaded_height = loadedBitmap.getHeight();
                croppedBitmap = Bitmap.createBitmap(desiredSize, desiredSize, Bitmap.Config.ARGB_8888);
                frameToCropTransform = ImageUtils.getTransformationMatrix(
                        loaded_width, loaded_height,
                        desiredSize, desiredSize,
                        0, true);
                final Canvas canvas = new Canvas(croppedBitmap);
                canvas.drawBitmap(loadedBitmap, frameToCropTransform, null);

                textureCopyBitmap = croppedBitmap;

                Toast.makeText(StyleTransferActivity.this, "Image has picked", Toast.LENGTH_LONG).show();
            } catch (FileNotFoundException e) {
                e.printStackTrace();
                Toast.makeText(StyleTransferActivity.this, "Something went wrong", Toast.LENGTH_LONG).show();
            }

        }else {
            Toast.makeText(StyleTransferActivity.this, "You haven't picked Image",Toast.LENGTH_LONG).show();
        }
    }

    private void stylizeImage(final Bitmap bitmap) {
        ++frameNum;
        bitmap.getPixels(intValues, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
        for (int i = 0; i < intValues.length; ++i) {
            final int val = intValues[i];
            floatValues[i * 3] = ((val >> 16) & 0xFF) / 255.0f;
            floatValues[i * 3 + 1] = ((val >> 8) & 0xFF) / 255.0f;
            floatValues[i * 3 + 2] = (val & 0xFF) / 255.0f;
        }

        inferenceInterface.feed(
                INPUT_NODE, floatValues, 1, bitmap.getWidth(), bitmap.getHeight(), 3);
        inferenceInterface.feed(STYLE_NODE, styleVals, NUM_STYLES);

        inferenceInterface.run(new String[] {OUTPUT_NODE}, false);
        inferenceInterface.fetch(OUTPUT_NODE, floatValues);

        for (int i = 0; i < intValues.length; ++i) {
            intValues[i] =
                    0xFF000000
                            | (((int) (floatValues[i * 3] * 255)) << 16)
                            | (((int) (floatValues[i * 3 + 1] * 255)) << 8)
                            | ((int) (floatValues[i * 3 + 2] * 255));
        }

        bitmap.setPixels(intValues, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
    }

    @Override
    protected void onResume() {
        super.onResume();
        initializeScreen();
    }

    public void addCallback(final OverlayView.DrawCallback callback) {
        final OverlayView overlay = (OverlayView) findViewById(R.id.debug_overlay);
        if (overlay != null) {
            overlay.addCallback(callback);
        }
    }

    public void invalidateOverlayView() {
        final OverlayView overlay = (OverlayView) findViewById(R.id.debug_overlay);
        if (overlay != null) {
            overlay.invalidate();
        }
    }

    public void applyStyleToLoadedImage() {
        final int loaded_width = loadedBitmap.getWidth();
        final int loaded_height = loadedBitmap.getHeight();
        croppedBitmap = Bitmap.createBitmap(desiredSize, desiredSize, Bitmap.Config.ARGB_8888);
        frameToCropTransform = ImageUtils.getTransformationMatrix(
                loaded_width, loaded_height,
                desiredSize, desiredSize,
                0, true);
        final Canvas canvas = new Canvas(croppedBitmap);
        canvas.drawBitmap(loadedBitmap, frameToCropTransform, null);

        if (desiredSize != initializedSize) {
            intValues = new int[desiredSize * desiredSize];
            floatValues = new float[desiredSize * desiredSize * 3];
            initializedSize = desiredSize;
        }
        stylizeImage(croppedBitmap);
        textureCopyBitmap = Bitmap.createBitmap(croppedBitmap);
    }

    public void initializeScreen() {
        inferenceInterface = new TensorFlowInferenceInterface(getAssets(), MODEL_FILE);

        addCallback(
                new OverlayView.DrawCallback() {
                    @Override
                    public void drawCallback(final Canvas canvas) {
                        renderScreen(canvas);
                    }
                });

        adapter = new ImageGridAdapter();
        grid = (GridView) findViewById(R.id.grid_layout);
        grid.setAdapter(adapter);
        grid.setOnTouchListener(gridTouchAdapter);
    }

    private final View.OnTouchListener gridTouchAdapter =
            new View.OnTouchListener() {
                ImageSlider slider = null;

                @Override
                public boolean onTouch(final View v, final MotionEvent event) {
                    switch (event.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            for (int i = 0; i < NUM_STYLES; ++i) {
                                final ImageSlider child = adapter.items[i];
                                final Rect rect = new Rect();
                                child.getHitRect(rect);
                                if (rect.contains((int) event.getX(), (int) event.getY())) {
                                    slider = child;
                                    slider.setHilighted(true);
                                }
                            }
                            break;

                        case MotionEvent.ACTION_MOVE:
                            if (slider != null) {
                                final Rect rect = new Rect();
                                slider.getHitRect(rect);

                                final float newSliderVal =
                                        (float)
                                                Math.min(
                                                        1.0,
                                                        Math.max(
                                                                0.0, 1.0 - (event.getY() - slider.getTop()) / slider.getHeight()));

                                setStyle(slider, newSliderVal);
                            }
                            break;

                        case MotionEvent.ACTION_UP:
                            if (slider != null) {
                                slider.setHilighted(false);
                                slider = null;

                                applyStyleToLoadedImage();

                                invalidateOverlayView();
                            }
                            break;

                        default: // fall out

                    }
                    return true;
                }
            };

    private void setStyle(final ImageSlider slider, final float value) {
        slider.setValue(value);

        if (NORMALIZE_SLIDERS) {
            // Slider vals correspond directly to the input tensor vals, and normalization is visually
            // maintained by remanipulating non-selected sliders.
            float otherSum = 0.0f;

            for (int i = 0; i < NUM_STYLES; ++i) {
                if (adapter.items[i] != slider) {
                    otherSum += adapter.items[i].value;
                }
            }

            if (otherSum > 0.0) {
                float highestOtherVal = 0;
                final float factor = otherSum > 0.0f ? (1.0f - value) / otherSum : 0.0f;
                for (int i = 0; i < NUM_STYLES; ++i) {
                    final ImageSlider child = adapter.items[i];
                    if (child == slider) {
                        continue;
                    }
                    final float newVal = child.value * factor;
                    child.setValue(newVal > 0.01f ? newVal : 0.0f);

                    if (child.value > highestOtherVal) {
                        lastOtherStyle = i;
                        highestOtherVal = child.value;
                    }
                }
            } else {
                // Everything else is 0, so just pick a suitable slider to push up when the
                // selected one goes down.
                if (adapter.items[lastOtherStyle] == slider) {
                    lastOtherStyle = (lastOtherStyle + 1) % NUM_STYLES;
                }
                adapter.items[lastOtherStyle].setValue(1.0f - value);
            }
        }

        final boolean lastAllZero = allZero;
        float sum = 0.0f;
        for (int i = 0; i < NUM_STYLES; ++i) {
            sum += adapter.items[i].value;
        }
        allZero = sum == 0.0f;

        // Now update the values used for the input tensor. If nothing is set, mix in everything
        // equally. Otherwise everything is normalized to sum to 1.0.
        for (int i = 0; i < NUM_STYLES; ++i) {
            styleVals[i] = allZero ? 1.0f / NUM_STYLES : adapter.items[i].value / sum;

            if (lastAllZero != allZero) {
                adapter.items[i].postInvalidate();
            }
        }
    }


    private class ImageGridAdapter extends BaseAdapter {
        final ImageSlider[] items = new ImageSlider[NUM_STYLES];
        final ArrayList<Button> buttons = new ArrayList<>();

        {
            final Button sizeButton =
                    new Button(StyleTransferActivity.this) {
                        @Override
                        protected void onMeasure(final int widthMeasureSpec, final int heightMeasureSpec) {
                            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                            setMeasuredDimension(getMeasuredWidth(), getMeasuredWidth());
                        }
                    };
            sizeButton.setText("" + desiredSize);
            sizeButton.setOnClickListener(
                    new View.OnClickListener() {
                        @Override
                        public void onClick(final View v) {
                            desiredSizeIndex = (desiredSizeIndex + 1) % SIZES.length;
                            desiredSize = SIZES[desiredSizeIndex];
                            sizeButton.setText("" + desiredSize);
                            sizeButton.postInvalidate();

                            applyStyleToLoadedImage();
                            invalidateOverlayView();
                        }
                    });

            final Button saveButton =
                    new Button(StyleTransferActivity.this) {
                        @Override
                        protected void onMeasure(final int widthMeasureSpec, final int heightMeasureSpec) {
                            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                            setMeasuredDimension(getMeasuredWidth(), getMeasuredWidth());
                        }
                    };
            saveButton.setText("save");
            saveButton.setTextSize(12);

            saveButton.setOnClickListener(
                    new View.OnClickListener() {
                        @Override
                        public void onClick(final View v) {
                            if (textureCopyBitmap != null) {
                                // TODO(andrewharp): Save as jpeg with guaranteed unique filename.
                                ImageUtils.saveBitmap(textureCopyBitmap, "stylized" + frameNum + ".png");
                                Toast.makeText(
                                        StyleTransferActivity.this,
                                        "Saved image to: /sdcard/tensorflow/" + "stylized" + frameNum + ".png",
                                        Toast.LENGTH_LONG)
                                        .show();
                            }
                        }
                    });

            buttons.add(sizeButton);
            buttons.add(saveButton);

            for (int i = 0; i < NUM_STYLES; ++i) {
                if (items[i] == null) {
                    final ImageSlider slider = new ImageSlider(StyleTransferActivity.this);
                    final Bitmap bm =
                            getBitmapFromAsset(StyleTransferActivity.this, "thumbnails/style" + i + ".jpg");
                    slider.setImageBitmap(bm);

                    items[i] = slider;
                }
            }
        }

        @Override
        public int getCount() {
            return buttons.size() + NUM_STYLES;
        }

        @Override
        public Object getItem(final int position) {
            if (position < buttons.size()) {
                return buttons.get(position);
            } else {
                return items[position - buttons.size()];
            }
        }

        @Override
        public long getItemId(final int position) {
            return getItem(position).hashCode();
        }

        @Override
        public View getView(final int position, final View convertView, final ViewGroup parent) {
            if (convertView != null) {
                return convertView;
            }
            return (View) getItem(position);
        }
    }

    public static Bitmap getBitmapFromAsset(final Context context, final String filePath) {
        final AssetManager assetManager = context.getAssets();

        Bitmap bitmap = null;
        try {
            final InputStream inputStream = assetManager.open(filePath);
            bitmap = BitmapFactory.decodeStream(inputStream);
        } catch (final IOException e) {
        }

        return bitmap;
    }

    private class ImageSlider extends ImageView {
        private float value = 0.0f;
        private boolean hilighted = false;

        private final Paint boxPaint;
        private final Paint linePaint;

        public ImageSlider(final Context context) {
            super(context);
            value = 0.0f;

            boxPaint = new Paint();
            boxPaint.setColor(Color.BLACK);
            boxPaint.setAlpha(128);

            linePaint = new Paint();
            linePaint.setColor(Color.WHITE);
            linePaint.setStrokeWidth(10.0f);
            linePaint.setStyle(Paint.Style.STROKE);
        }

        @Override
        public void onDraw(final Canvas canvas) {
            super.onDraw(canvas);
            final float y = (1.0f - value) * canvas.getHeight();

            // If all sliders are zero, don't bother shading anything.
            if (!allZero) {
                canvas.drawRect(0, 0, canvas.getWidth(), y, boxPaint);
            }

            if (value > 0.0f) {
                canvas.drawLine(0, y, canvas.getWidth(), y, linePaint);
            }

            if (hilighted) {
                canvas.drawRect(0, 0, getWidth(), getHeight(), linePaint);
            }
        }

        @Override
        protected void onMeasure(final int widthMeasureSpec, final int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            setMeasuredDimension(getMeasuredWidth(), getMeasuredWidth());
        }

        public void setValue(final float value) {
            this.value = value;
            postInvalidate();
        }

        public void setHilighted(final boolean highlighted) {
            this.hilighted = highlighted;
            this.postInvalidate();
        }
    }

    private void renderScreen(final Canvas canvas) {
        final Bitmap texture = textureCopyBitmap;
        if (texture != null) {
            final Matrix matrix = new Matrix();
            final float scaleFactor = Math.min(
                            (float) canvas.getWidth() / texture.getWidth(),
                            (float) canvas.getHeight() / texture.getHeight());
            matrix.postScale(scaleFactor, scaleFactor);
            canvas.drawBitmap(texture, matrix, new Paint());
        }
    }
}


