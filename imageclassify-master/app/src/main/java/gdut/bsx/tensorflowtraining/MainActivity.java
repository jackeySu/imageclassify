package gdut.bsx.tensorflowtraining;

import android.Manifest;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.os.MessageQueue;
import android.provider.MediaStore;
import android.provider.Settings;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.FileProvider;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.app.AppCompatDelegate;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;

import gdut.bsx.tensorflowtraining.ternsorflow.Classifier;
import gdut.bsx.tensorflowtraining.ternsorflow.TensorFlowImageClassifier;

/**
 * MainActivity
 * @author baishixian
 */
public class MainActivity extends AppCompatActivity implements View.OnClickListener {

    public static final String TAG = MainActivity.class.getSimpleName();

    private static final int OPEN_SETTING_REQUEST_COED = 110;
    private static final int TAKE_PHOTO_REQUEST_CODE = 120;
    private static final int PICTURE_REQUEST_CODE = 911;

    private static final int PERMISSIONS_REQUEST = 108;
    private static final int CAMERA_PERMISSIONS_REQUEST_CODE = 119;

    private static final String CURRENT_TAKE_PHOTO_URI = "currentTakePhotoUri";

    private static final int INPUT_SIZE = 299;//224
    private static final int IMAGE_MEAN = 128;//117
    private static final float IMAGE_STD = 128f;
    private static final String INPUT_NAME = "Mul";
    private static final String OUTPUT_NAME = "final_result";
    private static final String MODEL_FILE = "file:///android_asset/model/optimized_graph.pb";
    private static final String LABEL_FILE = "file:///android_asset/model/retrained_labels.txt";

    private Executor executor;
    private Uri currentTakePhotoUri;

    private TextView result;
    private ImageView ivPicture;
    private Classifier classifier;

    static {
        AppCompatDelegate.setCompatVectorFromResourcesEnabled(true);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!isTaskRoot()) {
            finish();
        }

        setContentView(R.layout.activity_main);

        findViewById(R.id.iv_choose_picture).setOnClickListener(this);
        findViewById(R.id.iv_take_photo).setOnClickListener(this);

        ivPicture = findViewById(R.id.iv_picture);
        result = findViewById(R.id.tv_classifier_info);

        // 避免耗时任务占用 CPU 时间片造成UI绘制卡顿，提升启动页面加载速度
        Looper.myQueue().addIdleHandler(idleHandler);

    }

    @Override
    public void onSaveInstanceState(Bundle savedInstanceState){
        // 防止拍照后无法返回当前 activity 时数据丢失
        savedInstanceState.putParcelable(CURRENT_TAKE_PHOTO_URI, currentTakePhotoUri);
        super.onSaveInstanceState(savedInstanceState);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        if (savedInstanceState != null) {
            currentTakePhotoUri = savedInstanceState.getParcelable(CURRENT_TAKE_PHOTO_URI);
        }
    }

    /**
     *  主线程消息队列空闲时（视图第一帧绘制完成时）处理耗时事件
     */
    MessageQueue.IdleHandler idleHandler = new MessageQueue.IdleHandler() {
        @Override
        public boolean queueIdle() {

            if (classifier == null) {
                // 创建 Classifier
               classifier = TensorFlowImageClassifier.create(MainActivity.this.getAssets(),
                       MODEL_FILE, LABEL_FILE, INPUT_SIZE, IMAGE_MEAN, IMAGE_STD, INPUT_NAME, OUTPUT_NAME);
            }

            // 初始化线程池
            executor = new ScheduledThreadPoolExecutor(1, new ThreadFactory() {
                @Override
                public Thread newThread(@NonNull Runnable r) {
                    Thread thread = new Thread(r);
                    thread.setDaemon(true);
                    thread.setName("ThreadPool-ImageClassifier");
                    return thread;
                }
            });

            // 请求权限
            requestMultiplePermissions();

            return false;
        }
    };

    /**
     * 请求存储和相机权限
     */
    private void requestMultiplePermissions() {

        String storagePermission = Manifest.permission.WRITE_EXTERNAL_STORAGE;
        String cameraPermission = Manifest.permission.CAMERA;

        int hasStoragePermission = ActivityCompat.checkSelfPermission(this, storagePermission);
        int hasCameraPermission = ActivityCompat.checkSelfPermission(this, cameraPermission);

        List<String> permissions = new ArrayList<>();
        if (hasStoragePermission != PackageManager.PERMISSION_GRANTED) {
            permissions.add(storagePermission);
        }

        if (hasCameraPermission != PackageManager.PERMISSION_GRANTED) {
            permissions.add(cameraPermission);
        }

        if (!permissions.isEmpty()) {
            String[] params = permissions.toArray(new String[permissions.size()]);
            ActivityCompat.requestPermissions(this, params, PERMISSIONS_REQUEST);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults)
    {
        if (requestCode == PERMISSIONS_REQUEST) {
            if (Manifest.permission.WRITE_EXTERNAL_STORAGE.equals(permissions[0]) && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                //permission denied 显示对话框告知用户必须打开权限 (storagePermission )
                // Should we show an explanation?
                // 当app完全没有机会被授权的时候，调用shouldShowRequestPermissionRationale() 返回false
                if (ActivityCompat.shouldShowRequestPermissionRationale(this,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                    // 系统弹窗提示授权
                    showNeedStoragePermissionDialog();
                } else {
                    // 已经被禁止的状态，比如用户在权限对话框中选择了"不再显示”，需要自己弹窗解释
                    showMissingStoragePermissionDialog();
                }
            }
        } else if (requestCode == CAMERA_PERMISSIONS_REQUEST_CODE) {
            if (grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                showNeedCameraPermissionDialog();
            } else {
                openSystemCamera();
            }
        }
    }

    /**
     *  显示缺失权限提示，可再次请求动态权限
     */
    private void showNeedStoragePermissionDialog() {
        new AlertDialog.Builder(this)
                .setTitle("权限获取提示")
                .setMessage("必须要有存储权限才能获取到图片")
                .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        dialog.cancel();
                    }
                })
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        ActivityCompat.requestPermissions(MainActivity.this,
                                new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERMISSIONS_REQUEST);
                    }
                }).setCancelable(false)
                .show();
    }


    /**
     *  显示权限被拒提示，只能进入设置手动改
     */
    private void showMissingStoragePermissionDialog() {
        new AlertDialog.Builder(this)
                .setTitle("权限获取失败")
                .setMessage("必须要有存储权限才能正常运行")
                .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        MainActivity.this.finish();
                    }
                })
                .setPositiveButton("去设置", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        startAppSettings();
                    }
                })
                .setCancelable(false)
                .show();
    }

    private void showNeedCameraPermissionDialog() {
        new AlertDialog.Builder(this)
                .setMessage("摄像头权限被关闭，请开启权限后重试")
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                })
                .create().show();
    }

    private static final String PACKAGE_URL_SCHEME = "package:";

    /**
     * 启动应用的设置进行授权
     */
    private void startAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse(PACKAGE_URL_SCHEME + getPackageName()));
        startActivityForResult(intent, OPEN_SETTING_REQUEST_COED);
    }

    @Override
    public void onClick(View view) {
        if (view.getId() == R.id.iv_choose_picture) {
            choosePicture();
        } else if (view.getId() == R.id.iv_take_photo) {
            takePhoto();
        }
    }

    /**
     * 选择一张图片并裁剪获得一个小图
     */
    private void choosePicture() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        startActivityForResult(intent, PICTURE_REQUEST_CODE);
    }

    /**
     * 使用系统相机拍照
     */
    private void takePhoto() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSIONS_REQUEST_CODE);
        } else {
            openSystemCamera();
        }
    }

    /**
     * 打开系统相机
     */
    private void openSystemCamera() {
        //调用系统相机
        Intent takePhotoIntent = new Intent();
        takePhotoIntent.setAction(MediaStore.ACTION_IMAGE_CAPTURE);

        //这句作用是如果没有相机则该应用不会闪退，要是不加这句则当系统没有相机应用的时候该应用会闪退
        if (takePhotoIntent.resolveActivity(getPackageManager()) == null) {
            Toast.makeText(this, "当前系统没有可用的相机应用", Toast.LENGTH_SHORT).show();
            return;
        }

        String fileName = "TF_" + System.currentTimeMillis() + ".jpg";
        File photoFile = new File(FileUtil.getPhotoCacheFolder(), fileName);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            //通过FileProvider创建一个content类型的Uri
            currentTakePhotoUri = FileProvider.getUriForFile(this, "gdut.bsx.tensorflowtraining.fileprovider", photoFile);
            //对目标应用临时授权该 Uri 所代表的文件
            takePhotoIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            currentTakePhotoUri = Uri.fromFile(photoFile);
        }

        //将拍照结果保存至 outputFile 的Uri中，不保留在相册中
        takePhotoIntent.putExtra(MediaStore.EXTRA_OUTPUT, currentTakePhotoUri);
        startActivityForResult(takePhotoIntent, TAKE_PHOTO_REQUEST_CODE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (resultCode == RESULT_OK) {
            if (requestCode == PICTURE_REQUEST_CODE) {
                // 处理选择的图片
                handleInputPhoto(data.getData());
            } else if (requestCode == OPEN_SETTING_REQUEST_COED){
                requestMultiplePermissions();
            } else if (requestCode == TAKE_PHOTO_REQUEST_CODE) {
                // 如果拍照成功，加载图片并识别
                handleInputPhoto(currentTakePhotoUri);
            }
        }
    }

    /**
     * 处理图片
     * @param imageUri
     */
    private void handleInputPhoto(Uri imageUri) {
        // 加载图片
        GlideApp.with(MainActivity.this).asBitmap().listener(new RequestListener<Bitmap>() {

            @Override
            public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<Bitmap> target, boolean isFirstResource) {
                Log.d(TAG,"handleInputPhoto onLoadFailed");
                Toast.makeText(MainActivity.this, "图片加载失败", Toast.LENGTH_SHORT).show();
                return false;
            }

            @Override
            public boolean onResourceReady(Bitmap resource, Object model, Target<Bitmap> target, DataSource dataSource, boolean isFirstResource) {
                Log.d(TAG,"handleInputPhoto onResourceReady");
                startImageClassifier(resource);
                return false;
            }
        }).load(imageUri).into(ivPicture);

        result.setText("Processing...");
    }

    /**
     * 开始图片识别匹配
     * @param bitmap
     */
    private void startImageClassifier(final Bitmap bitmap) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Log.i(TAG, Thread.currentThread().getName() + " startImageClassifier");
                    Bitmap croppedBitmap = getScaleBitmap(bitmap, INPUT_SIZE);

                    final List<Classifier.Recognition> results = classifier.recognizeImage(croppedBitmap);
                    final String mm=results.get(0).toString();
                    final char mm1=mm.charAt(1);


                    Log.i(TAG, "startImageClassifier results: " + results);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
//                            result.setText(String.format("识别结果: %s", results));
//                           final String print1=""+results;
//                            print1 +=mm2;
                            String mm2="[识别结果]："+results;
                            if(mm1=='0')mm2+="\n[卡路里值]可乐的热量为45大卡(每100毫升)\n[健康指南]碳酸饮料会损伤牙齿，易造成肥胖，宜少喝，多喝白开水";
                            else if(mm1=='1')mm2+="\n[卡路里值]饺子的热量为218大卡(每100克)\n[健康指南]饺子是碳水化合物、蛋白质、纤维和脂肪含量都比米饭高的一种食品，热量接近米饭的2倍，减肥期间可作为主食适量食用。 ";
                            else if(mm1=='2')mm2+="\n[卡路里值]炒饭的热量为188大卡(每100克)\n[健康指南]炒饭将肉类、蔬菜等混合米饭炒制而成，营养较白米饭丰富，热量接近米饭的2倍，减肥时可适量食用。 ";
                            else if(mm1=='3')mm2+="\n[卡路里值]汉堡的热量为292大卡（100克）\n[健康指南]汉堡的肉类营养价值较高，但油炸后能量很高，营养价值降低，减肥期间少食用。 ";
                            else if(mm1=='4')mm2+="\n[卡路里值]热狗的热量为307大卡（100克）\n[健康指南]热狗为高脂肪的烧烤食物，热量很高，钾含量很高，但钠含量更高，减肥时不宜食用。 ";
                            else if(mm1=='5')mm2+="\n[卡路里值]冰淇淋的热量为127大卡（100克）\n[健康指南]冰淇淋以饮用水、牛奶、奶粉、奶油（或植物油脂）、食糖等为主要原料制作，能量较高，减肥期间不宜多食。 ";
                            else if(mm1=='6')mm2+="\n[卡路里值]披萨的热量为235大卡（100克）\n[健康指南]披萨的脂肪含量不高，且营养搭配较为均衡，减肥期间可作为主食偶尔食用。 ";


//                            if(mm1=='9')mm2+="\n[卡路里值]雪碧的热量为45大卡(每100毫升)\n[健康指南]碳酸饮料会损伤牙齿，易造成肥胖，宜少喝，多喝白开水";
//                            else if(mm1=='3')mm2+="\n[卡路里值]饺子的热量为218大卡(每100克)\n[健康指南]饺子是碳水化合物、蛋白质、纤维和脂肪含量都比米饭高的一种食品，热量接近米饭的2倍，减肥期间可作为主食适量食用。 ";
//                            else if(mm1=='4')mm2+="\n[卡路里值]炒饭的热量为188大卡(每100克)\n[健康指南]炒饭将肉类、蔬菜等混合米饭炒制而成，营养较白米饭丰富，热量接近米饭的2倍，减肥时可适量食用。 ";
//                            else if(mm1=='0')mm2+="\n[卡路里值]巧克力的热量为589大卡（100克）\n[健康指南]高糖高油高热量，典型的增肥食物，减肥期间不宜食用。  ";
//                            else if(mm1=='5')mm2+="\n[卡路里值]热狗的热量为307大卡（100克）\n[健康指南]热狗为高脂肪的烧烤食物，热量很高，钾含量很高，但钠含量更高，减肥时不宜食用。 ";
//                            else if(mm1=='6')mm2+="\n[卡路里值]冰淇淋的热量为127大卡（100克）\n[健康指南]冰淇淋以饮用水、牛奶、奶粉、奶油（或植物油脂）、食糖等为主要原料制作，能量较高，减肥期间不宜多食。 ";
//                            else if(mm1=='1')mm2+="\n[卡路里值]曲奇的热量为546大卡（100克）\n[健康指南]曲奇脂肪和碳水化合物含量很高，热量很高，减肥期间不适宜食用。  ";
//                            else if(mm1=='2')mm2+="\n[卡路里值]纸杯蛋糕的热量为320大卡（100克）\n[健康指南]蛋糕脂肪含量高，能量大，减肥期间尽量少吃。   ";
//                            else if(mm1=='7')mm2+="\n[卡路里值]面条的热量为286大卡（100克）\n[健康指南]面条水分含量稍高，单位热量及碳水化合物，蛋白质的含量稍低，较易消化及吸收，适宜减肥期间食用。   ";
//                            else if(mm1=='8')mm2+="\n[卡路里值]粥的热量为47大卡（100克）\n[健康指南]一种碳水化合物和水分含量都较高的主食，粥比饭能更好的减少食物摄入，适宜减肥期间食用。 。  ";

                            result.setText(""+mm2);
                        }
                    });
                } catch (IOException e) {
                    Log.e(TAG, "startImageClassifier getScaleBitmap " + e.getMessage());
                    e.printStackTrace();
                }
            }
        });
    }


    /**
     * 对图片进行缩放
     * @param bitmap
     * @param size
     * @return
     * @throws IOException
     */
    private static Bitmap getScaleBitmap(Bitmap bitmap, int size) throws IOException {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        float scaleWidth = ((float) size) / width;
        float scaleHeight = ((float) size) / height;
        Matrix matrix = new Matrix();
        matrix.postScale(scaleWidth, scaleHeight);
        return Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true);
    }
}
