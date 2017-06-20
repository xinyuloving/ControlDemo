package com.example.mylibrary.base.mvp;

import android.accounts.NetworkErrorException;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.view.View;
import android.widget.Toast;
import com.example.mylibrary.base.BaseActivity;
import com.example.mylibrary.base.BaseModel;
import com.example.mylibrary.base.EndlessScrollListener;
import com.example.mylibrary.base.ProgressFragment;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Consumer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import timber.log.Timber;

/**
 * @param <CONTAINER> Activity容器类型
 * @param <ITEM> 列表对应的数据类型
 * @param <DATA> {@link BaseModel} 的泛型参数 如<b> BaseModel&lt;List&lt;User&gt;&gt;</b> 则&lt;DATA&gt;
 * 为List&lt;User&gt;
 * @param <P> 页面对应的Presenter
 */
public abstract class ListMVPFragment<CONTAINER extends BaseActivity, ITEM, DATA, P extends IListMVPPresenter>
    extends ProgressFragment<CONTAINER>
    implements IListMVPView<BaseModel<DATA>>, EndlessScrollListener.IMore {

  protected P mPresenter;

  private boolean mIsLoading;
  private boolean mIsError;
  private boolean mHasMore;

  protected final List<ITEM> mData = new ArrayList<>();

  @Override public void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
  }

  @Override public void onViewCreated(View view, Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);

    // 没有数据视图点击事件
    setEmptyViewClickListener(new View.OnClickListener() {
      @Override public void onClick(View v) {
        obtainData(false);
      }
    });
    // 网络异常视图点击事件
    setNetWorkErrorViewClickListener(new View.OnClickListener() {
      @Override public void onClick(View v) {
        obtainData(false);
      }
    });
  }

  /**
   * 调用后台服务之前执行的一些准备工作.
   * <pre>
   * 1. 如果正在加载,取消请求
   * 2. 更新当前状态 {@link #mIsLoading } = true {@link #mIsError} = false
   * 3. 将请求与Fragment的生命周期绑定 mDisposables.add(disposable)
   *
   * 使用方法:
   *  在服务调用处理中加入如下处理逻辑
   *  .doOnSubscribe(getDoOnSubscribe())//开始执行之前的准备工作
   *  .subscribeOn(AndroidSchedulers.mainThread())//指定 前面的doOnSubscribe 在主线程执行
   * </pre>
   *
   * @return {@link Consumer}
   */
  @Override public Consumer<? super Disposable> getDoOnSubscribe() {
    return new Consumer<Disposable>() {
      @Override public void accept(Disposable disposable) throws Exception {
        if (isLoading()) {// 如果正在加载,取消本次请求
          disposable.dispose();
          return;
        }
        mIsError = false;//加载正常
        mIsLoading = true;//加载中
        if (hasData()) {// 如果列表已经有数据,此时内容视图一定是可见的,不需要调用{@link #showConten}
          if (mIsViewCreated) onStatusUpdated();
        } else {// 加载的时候还没有数据,显示加载进度视图
          showProgress();
        }
        mDisposables.add(disposable);
      }
    };
  }

  /**
   * 服务端调用正常
   *
   * @param isRefresh 是否需要清空原来的数据
   * <pre>
   * 1. 更新状态 {@link #mIsLoading} = false ,{@link #mIsError} = false
   * 2. 服务调用成功后的回调 {@link #onSuccess(BaseModel, boolean)}
   * 3. 状态更新后的回调方法 {@link #onStatusUpdated()}
   * </pre>
   * @return {@link Consumer}
   */
  @Override public Consumer<BaseModel<DATA>> getOnNext(final boolean isRefresh) {
    return new Consumer<BaseModel<DATA>>() {
      @Override public void accept(BaseModel<DATA> data) throws Exception {
        mIsError = data.isSuccess();
        mIsLoading = false;
        boolean hasDataBefore = hasData();
        // 检查是否还有更多数据
        mHasMore = checkHasMore(data);

        // 服务调用成功的回调
        onSuccess(data, isRefresh);
        if (hasData()) {// 有数据显示内容
          showContent();
        } else {//没数据显示数据为空视图
          showEmpty();// 没有数据
        }

        // 如果服务调用之前有数据,或者调用之后有数据(也就是数据有无或者数据数量有变化),都需要更新
        if (hasDataBefore || hasData()) {
          if (mIsViewCreated) {
            onStatusUpdated();
          }
        }
      }
    };
  }

  /**
   * 服务调用异常处理
   *
   * @return {@link Consumer}
   */
  @Override public Consumer<? super Throwable> getOnError() {
    return new Consumer<Throwable>() {
      @Override public void accept(Throwable throwable) throws Exception {
        mIsError = true;//加载出错
        mIsLoading = false;//加载完成

        onError(throwable);// 错误回调

        if (hasData()) {
          if (mIsViewCreated) onStatusUpdated();//加载时候发生异常,更新界面显示
          return;// 有数据只弹出异常信息
        }

        // 网络异常显示,网络异常视图
        if (throwable instanceof NetworkErrorException
            || throwable instanceof TimeoutException
            || throwable instanceof IOException) {
          showNetWorkError();
        } else {// 没有数据,但是存编码问题抛出异常
          showEmpty(); // 普通异常显示空数据视图
        }
      }
    };
  }

  /**
   * 获取数据
   *
   * @param isRefresh 是否清空原来的数据
   */
  protected abstract void obtainData(boolean isRefresh);

  /**
   * 服务调用成功
   *
   * @param data 服务端返回的数据
   * @param isRefresh 是否需要清空原来的数据
   */
  protected abstract void onSuccess(BaseModel<DATA> data, boolean isRefresh);

  /**
   * 服务调用失败
   *
   * @param throwable {@link Throwable}
   */
  protected void onError(Throwable throwable) {
    Timber.e(throwable);
    Toast.makeText(getContext(), throwable.getMessage(), Toast.LENGTH_LONG).show();
  }

  /**
   * 根据返回的数据检查是否还有更多数据
   *
   * @param model {@link BaseModel}
   * @return {@link Boolean} true: 有更多数据
   */
  public abstract boolean checkHasMore(BaseModel<DATA> model);

  /**
   * 数据加载 [状态变更后] 回调此方法
   * <pre>
   * 仅在 <B>{@link #hasData()} == true</B> 列表有数据时调用
   *
   * 状态:
   * 1. 开始加载 {@link #mIsLoading} == true
   * 2. 加载完成 {@link #mIsLoading} == false
   * 3. 加载失败 {@link #mIsLoading} == false && {@link #isError()}  == true
   * </pre>
   */
  public abstract void onStatusUpdated();

  public boolean hasData() {
    return mData.size() > 0;
  }

  @Override public boolean isLoading() {
    return mIsLoading;
  }

  @Override public boolean isError() {
    return mIsError;
  }

  @Override public boolean hasMore() {
    return mHasMore;
  }

  @Override public void loadMore() {
    obtainData(false);
  }
}