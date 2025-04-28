package top.guoziyang.mydb.backend.common;

import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import top.guoziyang.mydb.common.Error;

/**
 * AbstractCache 实现了一个引用计数策略的缓存
 */
//抽象方法
public abstract class AbstractCache<T> {

    //引用缓存的设计 实现
    //这些成员变量共同维护缓存状态与线程同步，采用手动控制而非高层并发容器
    private HashMap<Long, T> cache;                     // 实际缓存的数据
    private HashMap<Long, Integer> references;          // 元素的引用个数
    private HashMap<Long, Boolean> getting;             // 标记正在被获取的 key，避免并发重复加载

    private int maxResource;                            // 缓存的最大缓存资源数
    private int count = 0;                              // 缓存中元素的个数
    private Lock lock;                                  // 互斥锁，保证线程安全，cache getting re....

    //构造方法？
    public AbstractCache(int maxResource) {
        this.maxResource = maxResource;
        cache = new HashMap<>();
        references = new HashMap<>();
        getting = new HashMap<>();
        lock = new ReentrantLock();
    }

    //get获取缓存资源方法
    //核心：保证只有一个线程操作修改资源
    protected T get(long key) throws Exception {
        while(true) {

            //获取锁失败会咋样？？

            lock.lock();//获取失败，会在此挂起
            //阻塞式加锁不会获取锁失败
            //获取不了锁就sleep
            if(getting.containsKey(key)) {
                // 请求的资源正在被其他线程获取，
                lock.unlock();
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    continue;
                }
                continue;
            }

            //接下来就保证线程安全了

            if(cache.containsKey(key)) {
                // 资源在缓存中，直接返回，引用增加
                T obj = cache.get(key);

                references.put(key, references.get(key) + 1);
                lock.unlock();
                return obj;
            }

            // 资源不在缓存中

            //缓存满了抛出异常
            if(maxResource > 0 && count == maxResource) {
                lock.unlock();
                throw Error.CacheFullException;
            }
            count ++;
            getting.put(key, true);
            lock.unlock();
            break;
        }

        T obj = null;
        try {
            //磁盘操作读取
            obj = getForCache(key);
        } catch(Exception e) {
            //放入缓存失败，回撤
            lock.lock();
            count --;
            getting.remove(key);
            lock.unlock();
            throw e;
        }

        lock.lock();
        //从磁盘获取之后，线程不再占有资源，移除
        getting.remove(key);
        cache.put(key, obj);
        //写入缓存
        references.put(key, 1);
        lock.unlock();
        
        return obj;
    }

    /**
     * 强行释放一个缓存
     */
    protected void release(long key) {
        lock.lock();
        try {
            int ref = references.get(key)-1;
            //当引用为0的时候才释放
            if(ref == 0) {
                //磁盘中/缓存中删除
                T obj = cache.get(key);
                releaseForCache(obj);
                references.remove(key);
                cache.remove(key);
                count --;
            } else {
                references.put(key, ref);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 关闭缓存，写回所有资源
     */
    protected void close() {
        lock.lock();
        try {
            Set<Long> keys = cache.keySet();
            for (long key : keys) {
                T obj = cache.get(key);
                releaseForCache(obj);
                references.remove(key);
                cache.remove(key);
            }
        } finally {
            lock.unlock();
        }
    }

    //TODO 维护两个抽象方法

    /**
     * 当资源不在缓存时的获取行为
     */
    protected abstract T getForCache(long key) throws Exception;
    /**
     * 当资源被驱逐时的写回行为
     */
    protected abstract void releaseForCache(T obj);
}
