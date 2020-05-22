package kr.co.jsh.feature.videoedit

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.*
import android.text.Layout
import android.util.DisplayMetrics
import android.util.Log
import android.view.*
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat.startActivityForResult
import androidx.core.content.ContextCompat.startActivity
import androidx.databinding.DataBindingUtil
import androidx.databinding.ObservableField
import androidx.databinding.ObservableFloat
import androidx.recyclerview.widget.LinearLayoutManager
import com.byox.drawview.enums.BackgroundScale
import com.byox.drawview.enums.BackgroundType
import com.byox.drawview.enums.DrawingCapture
import com.byox.drawview.views.DrawView
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import kotlinx.android.synthetic.main.activity_video_edit.*
import kotlinx.android.synthetic.main.progress_loading.*
import kotlinx.coroutines.*
import kr.co.domain.globalconst.Consts
import kr.co.domain.globalconst.PidClass
import kr.co.jsh.R
import kr.co.jsh.databinding.ActivityVideoEditBinding
import kr.co.jsh.dialog.DialogActivity
import kr.co.jsh.feature.fullscreen.VideoViewActivity
import kr.co.jsh.localclass.PausableDispatcher
import kr.co.jsh.singleton.UserObject
import kr.co.jsh.utils.*
import org.jetbrains.anko.runOnUiThread
import timber.log.Timber
import java.io.File
import org.koin.android.ext.android.get


class TrimmerActivity : AppCompatActivity(), TrimmerContract.View {
    private lateinit var binding: ActivityVideoEditBinding
    private lateinit var presenter : TrimmerPresenter
    private var screenSize = ObservableField<Int>()
    private lateinit var mSrc: Uri
    private var mFinalPath: String? = null
    private var crop_count = 0
    lateinit var crop_time: ArrayList<Pair<Int, Int>>
    private var timeposition = 0
    private var mDuration : Float = 0f
    private var touch_time = ObservableFloat()
    private var mStartPosition = 0f
    val texteColor : ObservableField<Array<Boolean>> = ObservableField(arrayOf(false,false,false,false,false))
    private var myPickBitmap : Bitmap? = null
    val mediaMetadataRetriever = MediaMetadataRetriever()
    val frameSecToSendServer = ArrayList<Int> ()
    private var realVideoSize = ArrayList<Int>()
    private lateinit var job: Job

    private val dispatcher =
        PausableDispatcher(Handler(Looper.getMainLooper()))

    private lateinit var mBitmaps: ArrayList<ArrayList<Bitmap>>

    var canUndo : ObservableField<Boolean> = ObservableField(false)
    var canRedo : ObservableField<Boolean> = ObservableField(false)

    private var videoOption = ""
    private var drawMaskCheck = false

    private var destinationPath: String
        get() {
            if (mFinalPath == null) {
                val folder = Environment.getExternalStorageDirectory()
                mFinalPath = folder.path + File.separator
            }
            return mFinalPath ?: ""
        }
        set(finalPath) {
            mFinalPath = finalPath
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupDataBinding()
        initView()
        setupDrawView()
    }

    private fun initView(){
        presenter = TrimmerPresenter(this, get(), get(), get())
        screenSize = ObservableField(ScreenSizeUtil(this).widthPixels/2)
        mBitmaps = ArrayList()
        setupPermissions(this) {
            val extraIntent = intent
            presenter.prepareVideoPath(extraIntent)
        }

        binding.handlerTop.progress =  binding.handlerTop.max / 2
        binding.handlerTop.isEnabled = false

        crop_time = arrayListOf() //initialize
        crop_time.add(Pair(0, 0))//1

        mDuration = binding.videoLoader.duration.toFloat()
        binding.videoLoader.setOnPreparedListener {
                mp -> onVideoPrepared(mp) }
    }

    private fun setupDataBinding(){
        binding = DataBindingUtil.setContentView(this, R.layout.activity_video_edit)
        binding.trimmer = this@TrimmerActivity
    }

    private fun setupDrawView(){
        binding.videoFrameView.setOnDrawViewListener(object : DrawView.OnDrawViewListener {
            override fun onEndDrawing() {
                canUndoRedo()
            }

            override fun onStartDrawing() {
                canUndoRedo()
            }

            override fun onClearDrawing() {
                canUndoRedo()
            }

            override fun onAllMovesPainted() {
                canUndoRedo()
            }

            override fun onRequestText() {
                TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
            }
        })
    }


    private fun onVideoPrepared(mp: MediaPlayer) {
        val lp = binding.videoLoader.layoutParams

        binding.handlerTop.visibility=View.VISIBLE
        binding.videoLoader.layoutParams = lp
        mDuration = binding.videoLoader.duration.toFloat()
        realVideoSize.add(mp.videoWidth)
        realVideoSize.add(mp.videoHeight)

        setTimeFrames()
        onVideoPrepared()
    }

    private fun onVideoCompleted() {
        binding.videoLoader.seekTo(mStartPosition.toInt())
    }

    fun playVideo() {
        binding.videoLoader.setOnCompletionListener{
            binding.iconVideoPlay.isSelected = false
            onVideoCompleted()
            dispatcher.cancel()
        }

        if (binding.videoLoader.isPlaying) {
            binding.iconVideoPlay.isSelected = false
            timeposition = binding.videoLoader.currentPosition
            binding.videoLoader.seekTo(timeposition)
            binding.videoLoader.pause()
            dispatcher.pause()

        } else {
            texteColor.set(arrayOf(false,false,false,false))
            binding.iconVideoPlay.isSelected = true
            binding.videoLoader.setOnPreparedListener {
               mp ->
                mp.setOnSeekCompleteListener {
                    binding.videoLoader.seekTo(timeposition)
                    }
            }
            binding.videoLoader.start()
            startThread()
            dispatcher.resume()
        }
    }

    //지울 객체 그리기
    fun removeMode(){
        drawMaskCheck = true
        if(crop_count < 2) {
            Toast.makeText(this, "구간을 먼저 잘라주세요", Toast.LENGTH_LONG).show()
        } else {
            binding.iconVideoPlay.isSelected = false
            binding.videoLoader.pause()
            binding.videoFrameView.setBackgroundResource(R.color.background_space)
            mediaMetadataRetriever.setDataSource(this, mSrc)
            //지울 곳의 프레임위치
            myPickBitmap = mediaMetadataRetriever.getFrameAtTime(
                touch_time.get().toLong() * 1000,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            )
            binding.videoFrameView.setBackgroundImage(
                myPickBitmap as Bitmap,
                BackgroundType.BITMAP,
                BackgroundScale.FIT_START
            )

            Toast.makeText(this, "지울 곳을 칠해주세요", Toast.LENGTH_LONG).show()

            texteColor.set(arrayOf(false, false, false, false, false))
            texteColor.set(arrayOf(true, false, false, false, false))

            hideVideoView()
            //미리 서버에 올리기
            presenter.trimVideo(destinationPath, this, mSrc, frameSecToSendServer[0], frameSecToSendServer[1])

        }
    }

    private fun hideVideoView(){
        if(binding.videoLoader.visibility == View.VISIBLE) {
            binding.videoLoader.visibility = View.INVISIBLE
            binding.videoFrameView.visibility = View.VISIBLE
        }
    }

    fun resetTimeLineView(){
        binding.iconVideoPlay.isSelected = false
        binding.videoLoader.pause()
        crop_count = 0
        presenter.resetCrop(this, crop_time)
        binding.boader1.visibility = View.INVISIBLE
        binding.boader2.visibility = View.INVISIBLE

        texteColor.set(arrayOf(false,false,false,false, false))
        texteColor.set(arrayOf(false,true,false,false, false))
    }



    override fun onError(message: String) {
        Timber.e(message)
    }

    private fun startThread() {
      GlobalScope.launch(dispatcher) {
            if (this.isActive) {
                suspendFunc()
            }
        }
    }

    private suspend fun suspendFunc() {
        while ( binding.videoLoader.isPlaying) {
            applicationContext.runOnUiThread {
                binding.textStartTime.text = String.format(
                    "%s",
                    TrimVideoUtils.stringForTime( binding.videoLoader.currentPosition.toFloat())
                )
                //시간 흐를때마다 뷰 옆으로 이동!
                binding.scroll.scrollTo(
                    ( binding.videoLoader.currentPosition * (binding.timeLineViewRecycler.width - ScreenSizeUtil(
                        applicationContext
                    ).widthPixels)) /  binding.videoLoader.duration, 0
                )
            }
            delay(1)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setTimeFrames() {
        binding.textTimeSelection.text =
            String.format("%s", TrimVideoUtils.stringForTime(mDuration))
        binding.textStartTime.text = String.format(
            "%s",
            TrimVideoUtils.stringForTime(binding.videoLoader.currentPosition.toFloat())
        )


        binding.timeLineViewRecycler.setOnTouchListener { _: View, motionEvent: MotionEvent ->
            when (motionEvent.action) {
                //편집할 영역을 선택하기
                MotionEvent.ACTION_UP, MotionEvent.ACTION_DOWN -> {
                    try {
                        if (crop_count == 2) {
                            binding.timeLineViewRecycler.performClick()
                            setBoarderRange(motionEvent.x)
                            Log.i("touch x coordi:", "${motionEvent.x}")
                            true
                        } else {
                            false
                        }
                    } catch (e: Exception) {
                        false
                    }
                }
                else -> false
            }
        }

        binding.scroll.setOnScrollChangeListener { view: View, scrollX: Int, scrollY: Int, oldScrollX: Int, oldScrollY: Int ->
            if (scrollX != oldScrollX && !binding.videoLoader.isPlaying) {
                Observable.just(binding.scroll.scrollX)
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe({
                        //showVideoView() //재생 버튼 누른 후 remove 하면 이상하게 동작함. 무조건 seekbar로 이동시에만 정상동작.
                        binding.videoLoader.visibility = View.VISIBLE
                        binding.videoFrameView.visibility = View.INVISIBLE

                        touch_time.set(
                            (mDuration * it) / ((binding.timeLineViewRecycler.width) - ScreenSizeUtil(
                                this
                            ).widthPixels)
                        )
                        binding.videoLoader.seekTo(touch_time.get().toInt())
                        binding.textStartTime.text = String.format(
                            "%s",
                            TrimVideoUtils.stringForTime(touch_time.get())
                        )
                        Timber.i("test!!")
                        timeposition = touch_time.get().toInt()

                    }, {
                        Timber.i(it.localizedMessage)
                    })
            }
        }

    }


    //coordiX: 사용자가 터치한 좌표의 X값을 가져옴 (상대좌표)
    private fun setBoarderRange(coordiX:Float){
        //터치한 곳에서 width/2 를 빼야지 원활한 계산이 가능해짐
        var params = FrameLayout.LayoutParams(0,0)
        var startX = coordiX - ScreenSizeUtil(this).widthPixels/2
        Log.i("startX:","$startX}")
        if(startX >= crop_time[1].first && startX <= crop_time[2].first){
            binding.border.visibility = View.INVISIBLE
            //7은 TimeLintView 에서 그려줄 때 만든 margin 값
            params = FrameLayout.LayoutParams(crop_time[2].first - crop_time[1].first, binding.timeLineViewRecycler.height-10)
            params.marginStart = ScreenSizeUtil(this).widthPixels/2 + crop_time[1].first
            binding.border.layoutParams = params
            binding.border.visibility = View.VISIBLE

            frameSecToSendServer.apply{
                clear()
                add(crop_time[1].second)
                add(crop_time[2].second)
            }
        }
        else if(startX > crop_time[2].first){
            binding.border.visibility = View.INVISIBLE
            params = FrameLayout.LayoutParams(crop_time[3].first - crop_time[2].first,  binding.timeLineViewRecycler.height-10)
            params.marginStart = ScreenSizeUtil(this).widthPixels/2 + crop_time[2].first
            binding.border.layoutParams = params
            binding.border.visibility = View.VISIBLE

            frameSecToSendServer.apply{
                clear()
                add(crop_time[2].second)
                add(crop_time[3].second)
            }

        }
        else if (startX >= 0 && startX < crop_time[1].first) {
            binding.border.visibility = View.INVISIBLE
            params = FrameLayout.LayoutParams(crop_time[1].first - crop_time[0].first,  binding.timeLineViewRecycler.height-10)
            params.marginStart = ScreenSizeUtil(this).widthPixels/2
            binding.border.layoutParams = params
            binding.border.visibility = View.VISIBLE

            frameSecToSendServer.apply{
                clear()
                add(crop_time[0].second)
                add(crop_time[1].second)
            }

        }
        else{
            binding.border.visibility = View.INVISIBLE
        }

    }

    fun clearDraw(){
        if(binding.videoFrameView.visibility == View.INVISIBLE){
            Toast.makeText(this,"지울 객체를 먼저 선택하세요.",Toast.LENGTH_LONG).show()
        }
        else {
            binding.iconVideoPlay.isSelected = false
            binding.videoFrameView.restartDrawing()
            removeMode()
            texteColor.set(arrayOf(false, false, false, false, false))
            texteColor.set(arrayOf(false, false, true, false, false))
        }
    }


    fun cropVideo(){
        crop_count ++
        presenter.crop(this, crop_count, video_loader, crop_time, binding.timeLineViewRecycler)
        greyline()
    }

    private fun greyline() {
        val param1 = FrameLayout.LayoutParams(7,FrameLayout.LayoutParams.MATCH_PARENT)
        val param2 = FrameLayout.LayoutParams(7,FrameLayout.LayoutParams.MATCH_PARENT)

        param1.setMargins(crop_time[1].first + ScreenSizeUtil(this).widthPixels/2,0,0,0)

        binding.boader1.apply {
            layoutParams = param1
            visibility = View.VISIBLE
        }
        param2.setMargins(crop_time[2].first + ScreenSizeUtil(this).widthPixels/2 ,0,0,0)
        binding.boader2.apply{
            layoutParams = param2
            visibility = View.VISIBLE
        }



    }

    override fun onVideoPrepared() {
        RunOnUiThread(this).safely {
           // Toast.makeText(this, "onVideoPrepared", Toast.LENGTH_SHORT).show()
        }
    }


    private fun destroy() {
        BackgroundExecutor.cancelAll("", true)
        UiThreadExecutor.cancelAll("")
    }

    override fun cancelAction() {
        RunOnUiThread(this).safely {
            this.destroy()
            finish()
        }
    }

    override fun onTrimStarted() {
        RunOnUiThread(this).safely {
           Timber.i("Started Trimming")
        }
    }

    override fun videoPath(path: String) {
        mSrc= Uri.parse(path)
        binding.videoLoader.setVideoURI(mSrc)
        binding.videoLoader.requestFocus()
        presenter.getThumbnailList(mSrc, this)
        destinationPath = Environment.getExternalStorageDirectory().toString() + File.separator + "returnable" + File.separator + "Videos" + File.separator
    }

    override fun setPairList(list: ArrayList<Pair<Int, Int>>) {
        crop_time = list
    }

    override fun resetCropView() {
        binding.border.visibility = View.INVISIBLE
    }

    override fun setThumbnailListView(thumbnailList: ArrayList<Bitmap>) {
        mBitmaps.add(thumbnailList)

        binding.timeLineViewRecycler.apply{
            layoutManager = LinearLayoutManager(context)
            adapter = TrimmerAdapter(mBitmaps, context)
        }
    }

    fun backButton(){
        finish()
    }

    fun sendRemoveVideoInfoToServer(){
        videoOption = Consts.DEL_OBJ
        if(drawMaskCheck && frameSecToSendServer.isNotEmpty()) {
            texteColor.set(arrayOf(false, false, false, false, false))
            texteColor.set(arrayOf(false, false, false, true, false))

            job = CoroutineScope(Dispatchers.Main).launch {
                showProgressbar()
                CoroutineScope(Dispatchers.Default).async {
                    //Todo 서버로 자른 비디오, frametimesec, maskimg 전송
                    val maskImg = binding.videoFrameView.createCapture(DrawingCapture.BITMAP)
                    maskImg?.let {
                        //자세한 코드설명은 PhotoActivity에 있음.
                        val cropBitmap = CropBitmapImage(
                            maskImg[0] as Bitmap,
                            binding.videoFrameView.width,
                            binding.videoFrameView.height
                        )
                        val resizeBitmap =
                            ResizeBitmapImage(cropBitmap, realVideoSize[0], realVideoSize[1])
                        val binaryMask = CreateBinaryMask(resizeBitmap)

                        //마스크 전송
                        presenter.uploadMaskFile(binaryMask, touch_time.get(), applicationContext)
                        //progressDialog.dismiss()
                    }
                }.await()
            }
            if(UserObject.loginResponse == 200) job.start()
            else {
                Toast.makeText(this, "로그인을 먼저 해주세요.", Toast.LENGTH_SHORT).show()
                cancelJob()
            }
        }
        else {
            Toast.makeText(applicationContext, "마스크를 먼저 그려주세요", Toast.LENGTH_SHORT)
                .show()
        }

    }

    fun sendImproveVideoInfoToServer(){
        videoOption = Consts.SUPER_RESOL
        if(crop_count < 2) {
            Toast.makeText(this, "구간을 먼저 잘라주세요", Toast.LENGTH_LONG).show()
        } else {
            texteColor.set(arrayOf(false, false, false, false, false))
            texteColor.set(arrayOf(false, false, false, false, true))
            //미리 서버에 올리기
            job = CoroutineScope(Dispatchers.Main).launch {
                showProgressbar()
                CoroutineScope(Dispatchers.Default).async {
                    presenter.trimVideo(
                        destinationPath,
                        applicationContext,
                        mSrc,
                        frameSecToSendServer[0],
                        frameSecToSendServer[1]
                    )
                }.await()
            }
        }
    }


    fun fullScreen(){
        val intent = Intent(this, VideoViewActivity::class.java).apply{
            putExtra(Consts.VIDEO_URI, mSrc.toString())
            putExtra(Consts.VIDEO_CURRENT_POSITION, binding.videoLoader.currentPosition)
        }
        startActivityForResult(intent, 1000)
    }

    private fun showProgressbar(){
        val intent = Intent(this, DialogActivity::class.java)
        startActivity(intent)
    }

    override fun getResult(uri: Uri) {
        //Todo Trim 된 결과가 여기로 넘어오고 다시 getResultUri로 들어감
        presenter.getResultUri(uri, this, videoOption)
    }

    override fun uploadSuccess(msg: String) {
       Timber.e(msg)
    }

    override fun uploadFailed(msg: String) {
        Timber.e(msg)
    }

    override fun cancelJob() {
        job.cancel()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if(requestCode == 1000 && resultCode == 1000 ){
            dispatcher.resume()
        }
    }

    fun undoButton(){
            binding.videoFrameView.undo()
            canUndoRedo()
    }

    fun redoButton(){
            binding.videoFrameView.redo()
            canUndoRedo()
    }

    private fun canUndoRedo(){
        if(binding.videoFrameView.canUndo()) {
            canUndo.set(true)
        } else {
            canUndo.set(false)
        }

        if(binding.videoFrameView.canRedo()) {
            canRedo.set(true)
        }
        else {
            canRedo.set(false)
        }
    }

    override fun onStop() {
        super.onStop()
        Timber.e("onStop")
    }

    override fun onResume() {
        super.onResume()
        Timber.e("onResume")
    }

    override fun onPause() {
        super.onPause()
        Timber.e("onPause")
        dispatcher.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        mBitmaps.clear()
        PidClass.apply{
            videoMaskObjectPid = ""
            videoObjectPid = ""
        }
    }
}