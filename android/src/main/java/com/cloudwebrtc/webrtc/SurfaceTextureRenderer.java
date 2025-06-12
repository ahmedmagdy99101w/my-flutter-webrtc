package com.cloudwebrtc.webrtc;

import android.graphics.SurfaceTexture;
import android.view.Surface;
import android.util.Log;

import org.webrtc.EglBase;
import org.webrtc.EglRenderer;
import org.webrtc.GlRectDrawer;
import org.webrtc.RendererCommon;
import org.webrtc.ThreadUtils;
import org.webrtc.VideoFrame;

import java.util.concurrent.CountDownLatch;

import io.flutter.view.TextureRegistry;

/**
 * Display the video stream on a Surface.
 * renderFrame() is asynchronous to avoid blocking the calling thread.
 * This class is thread safe and handles access from potentially three different threads:
 * Interaction from the main app in init, release and setMirror.
 * Interaction from C++ rtc::VideoSinkInterface in renderFrame.
 * Interaction from SurfaceHolder lifecycle in surfaceCreated, surfaceChanged, and surfaceDestroyed.
 */
public class SurfaceTextureRenderer extends EglRenderer {
  private static final String TAG = "SurfaceTextureRenderer";

  // Callback for reporting renderer events. Read-only after initilization so no lock required.
  private RendererCommon.RendererEvents rendererEvents;
  private final Object layoutLock = new Object();
  private boolean isRenderingPaused;
  private boolean isFirstFrameRendered;
  private int rotatedFrameWidth;
  private int rotatedFrameHeight;
  private int frameRotation;

  /**
   * In order to render something, you must first call init().
   */
  public SurfaceTextureRenderer(String name) {
    super(name);
  }

  public void init(final EglBase.Context sharedContext,
                   RendererCommon.RendererEvents rendererEvents) {
    init(sharedContext, rendererEvents, EglBase.CONFIG_PLAIN, new GlRectDrawer());
  }

  /**
   * Initialize this class, sharing resources with |sharedContext|. The custom |drawer| will be used
   * for drawing frames on the EGLSurface. This class is responsible for calling release() on
   * |drawer|. It is allowed to call init() to reinitialize the renderer after a previous
   * init()/release() cycle.
   */
  public void init(final EglBase.Context sharedContext,
                   RendererCommon.RendererEvents rendererEvents, final int[] configAttributes,
                   RendererCommon.GlDrawer drawer) {
    ThreadUtils.checkIsOnMainThread();
    this.rendererEvents = rendererEvents;
    synchronized (layoutLock) {
      isFirstFrameRendered = false;
      rotatedFrameWidth = 0;
      rotatedFrameHeight = 0;
      frameRotation = -1;
    }
    super.init(sharedContext, configAttributes, drawer);
  }

  @Override
  public void init(final EglBase.Context sharedContext, final int[] configAttributes,
                   RendererCommon.GlDrawer drawer) {
    init(sharedContext, null /* rendererEvents */, configAttributes, drawer);
  }

  /**
   * Limit render framerate.
   *
   * @param fps Limit render framerate to this value, or use Float.POSITIVE_INFINITY to disable fps
   *            reduction.
   */
  @Override
  public void setFpsReduction(float fps) {
    synchronized (layoutLock) {
      isRenderingPaused = fps == 0f;
    }
    super.setFpsReduction(fps);
  }

  @Override
  public void disableFpsReduction() {
    synchronized (layoutLock) {
      isRenderingPaused = false;
    }
    super.disableFpsReduction();
  }

  @Override
  public void pauseVideo() {
    synchronized (layoutLock) {
      isRenderingPaused = true;
    }
    super.pauseVideo();
  }

  // VideoSink interface.
  @Override
  public void onFrame(VideoFrame frame) {
    if (surface == null && producer != null) {
      producer.setSize(frame.getRotatedWidth(), frame.getRotatedHeight());
      surface = producer.getSurface();
      if (surface != null) {
        createEglSurface(surface);
      }
    }
    updateFrameDimensionsAndReportEvents(frame);
    super.onFrame(frame);
  }

  private Surface surface = null;
  private TextureRegistry.SurfaceProducer producer;

  public void surfaceCreated(final TextureRegistry.SurfaceProducer producer) {
    ThreadUtils.checkIsOnMainThread();
    this.producer = producer;

    // Callback implementation compatible with your Flutter version
    this.producer.setCallback(
            new TextureRegistry.SurfaceProducer.Callback() {
              @Override
              public void onSurfaceCreated() {
                Log.d(TAG, "onSurfaceCreated called");
                synchronized (layoutLock) {
                  if (surface == null && producer != null) {
                    surface = producer.getSurface();
                    if (surface != null) {
                      createEglSurface(surface);
                      if (rotatedFrameWidth > 0 && rotatedFrameHeight > 0) {
                        producer.setSize(rotatedFrameWidth, rotatedFrameHeight);
                      }
                    }
                  }
                }
              }

              @Override
              public void onSurfaceDestroyed() {
                Log.d(TAG, "onSurfaceDestroyed called");
                surfaceDestroyed();
              }
            }
    );
  }

  public void surfaceDestroyed() {
    ThreadUtils.checkIsOnMainThread();
    Log.d(TAG, "surfaceDestroyed called");

    final CountDownLatch completionLatch = new CountDownLatch(1);
    releaseEglSurface(completionLatch::countDown);
    ThreadUtils.awaitUninterruptibly(completionLatch);

    synchronized (layoutLock) {
      if (surface != null) {
        surface.release();
        surface = null;
      }
    }

    if (producer != null) {
      producer.setCallback(null); // Remove callback to prevent additional calls
    }
  }

  // Update frame dimensions and report any changes to |rendererEvents|.
  private void updateFrameDimensionsAndReportEvents(VideoFrame frame) {
    synchronized (layoutLock) {
      if (isRenderingPaused) {
        return;
      }
      if (!isFirstFrameRendered) {
        isFirstFrameRendered = true;
        if (rendererEvents != null) {
          rendererEvents.onFirstFrameRendered();
        }
      }
      if (rotatedFrameWidth != frame.getRotatedWidth()
              || rotatedFrameHeight != frame.getRotatedHeight()
              || frameRotation != frame.getRotation()) {
        if (rendererEvents != null) {
          rendererEvents.onFrameResolutionChanged(
                  frame.getBuffer().getWidth(), frame.getBuffer().getHeight(), frame.getRotation());
        }
        rotatedFrameWidth = frame.getRotatedWidth();
        rotatedFrameHeight = frame.getRotatedHeight();
        if (producer != null) {
          producer.setSize(rotatedFrameWidth, rotatedFrameHeight);
        }
        frameRotation = frame.getRotation();
      }
    }
  }
}