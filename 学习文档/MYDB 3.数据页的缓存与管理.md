
### 数据页缓存

DM模块向下对文件系统的抽象，DM将其抽象成页面，每次对文件系统的读写都是以页面为单位的。同样的，缓存也是以页面为主进行缓存的。



定义一个页面：

```java
public class PageImpl implements Page {
	private int pageNumber; 
	private byte[] data;
	private boolean dirty; 
	private Lock lock; 
	private PageCache pc; 
}
```

其中，pageNumber 是这个页面的页号，**该页号从 1 开始**。
data 就是这个页实际包含的字节数据。dirty 标志着这个页面是否是脏页面，在**缓存驱逐**的时候，脏页面(**访问/修改过**)需要被写回磁盘。这里保存了一个 PageCache（还未定义）的引用，用来方便在拿到 Page 的引用时可以快速对这个页面的缓存进行释放操作。


页面缓存的具体实现类，需要继承抽象的缓存框架，以及`getForCache()`和`releaseForCache()`两个抽象方法。由于数据源就是文件系统，`getForCache()`直接从文件读取，并包裹成Page:


```java
@Override  
protected Page getForCache(long key) throws Exception {  
    int pgno = (int)key;  
    long offset = PageCacheImpl.pageOffset(pgno);  
  
    ByteBuffer buf = ByteBuffer.allocate(PAGE_SIZE);  
    fileLock.lock();  
    try {  
        fc.position(offset);  
        fc.read(buf);  
    } catch(IOException e) {  
        Panic.panic(e);  
    }  
    fileLock.unlock();  
    //读文件，返回page  
    return new PageImpl(pgno, buf.array(), this);  
}  
  
```

而 `releaseForCache()` 驱逐页面时，也只需要根据页面是否是脏页面，来决定是否需要写回文件系统。




PageCache 还使用了一个 AtomicInteger，来记录了当前打开的数据库文件有多少页。这个数字在数据库文件被打开时就会被计算，并在<u>新建页面时自增</u>。

```java
public int newPage(byte[] initData) {  
    int pgno = pageNumbers.incrementAndGet();  
    Page pg = new PageImpl(pgno, initData, null);  
    flush(pg);  
    return pgno;  
}
```




### 数据页管理


#### 第一页

数据库文件的第一页，通常有着特殊用途，不如储存一些元数据，用于启动检查什么的。MYDB的第一页，只是用来做启动检查。

具体的原理是，启动时，随机生成字节，储存在100 - 107 字节。数据库正常关闭时，储存在108 - 115字节。 （是的，有小概率的风险出错）



所以，每次启动的时候，检查两处是否相同，从而判断上一次是否为正常关闭，若为正常关闭，则执行数据的恢复流程。


#### 普通页

 一个普通页面以一个2字节无符号数起始，表示这一页空闲位置的偏移。剩下的是实际储存的数据。
对于普通页的管理，基本都是围绕FSO(Free Space Offset) 进行的。例如向页面插入数据：

我们先来看看普通页设计的一些要求 

1. 如何**组织数据**以便高效存取
2. 如何**动态维护空闲空间**以便支持多次插入。
3. 如何**支持恢复机制**（如崩溃恢复，日志回滚）。

我们来试着体会一下，系统要不断写入数据，怎么填？如果是直接写，每次写入还需要判断空闲位置，这样性能很低，

我们引入 **FSO**， 顾名思义 FSO指向的是Data区域中尚未被占用的第一个字节的位置，插入新的位置时，我们可以直接从FSO指向的位置插入，从而无需扫描空闲区。

这样维护了数据内部的紧凑性以及有序性。

```java
private static void setFSO(byte[] raw, short ofData) {  
    //刷新FSO 0 - 1字节  
    //(Object src, int srcPos, Object dest, int destPos, int length)  
    System.arraycopy(Parser.short2Byte(ofData), 0, raw, OF_FREE, OF_DATA);  
}  
  
// 获取pg的FSO  
public static short getFSO(Page pg) {  
    return getFSO(pg.getData());  
}  
  
private static short getFSO(byte[] raw) {  
    return Parser.parseShort(Arrays.copyOfRange(raw, 0, 2));  
}  
  
// 将raw插入pg中，返回插入位置  
public static short insert(Page pg, byte[] raw) {  
    pg.setDirty(true);  
    short offset = getFSO(pg.getData());  
    System.arraycopy(raw, 0, pg.getData(), offset, raw.length);  
    setFSO(pg.getData(), (short)(offset + raw.length));  
    return offset;  
}
```


数据库异常关闭后，恢复例程直接插入数据以及修改数据使用。

```java
public static void recoverInsert(Page pg, byte[] raw, short offset) {  
    pg.setDirty(true);  
    System.arraycopy(raw, 0, pg.getData(), offset, raw.length);  
  
    short rawFSO = getFSO(pg.getData());  
    //异常关闭之后，offset可能指向不那么空闲的？  
    //还是因为异常关闭之后，还未return offset就崩溃了？  
    if(rawFSO < offset + raw.length) {  
        setFSO(pg.getData(), (short)(offset+raw.length));  
    }  
}  
  
// 将raw插入pg中的offset位置，不更新update  
public static void recoverUpdate(Page pg, byte[] raw, short offset) {  
    pg.setDirty(true);  
    System.arraycopy(raw, 0, pg.getData(), offset, raw.length);  
}
```



一开始还在疑惑：offset 不就是指向了空闲区吗，为什么还要对比？

**明确概念 成员含义** 是理解代码的第一步！

emm感觉这些操作都蛮耗时，以页为单位在磁盘







# 模块名称 / 主题名称

## 1. 模块概述   Page


> 该模块的职责是什么？在整个系统架构中的地位？
> 和其他模块的交互？

_DM 将文件系统抽象成页面，每次对文件系统的读写都是以页面为单位的。同样，从文件系统读进来的数据也是以页面为单位进行缓存的。

_和借用MYDB2实现的通用缓存框架_


定义一个页面：
``` java
public class PageImpl implementd Page {
	private int pageNumber;
	private byte[] data;
	private boolean dirty; //标志是否脏页面
	private Lock loak;

	private PageCache pc;
}
```





## 2. 功能与设计思想
- 模块的核心功能点？
- 使用了哪些设计模式或编程思想？（如引用计数、缓存淘汰、并发控制等）
- 为什么这样设计，有哪些权衡？

## 3. 类与关键数据结构解析
- 关键类介绍：成员变量、构造方法、设计逻辑
- 并发控制策略、生命周期管理、状态变量说明

## 4. 核心方法流程解析
- 主要方法逐个拆解（建议配合流程图）
- 异常处理、线程安全、边界条件等细节分析

## 5. 相关技术补充
- 缓存策略选型（如 LRU vs LFU vs 引用计数）
- ReentrantLock 使用细节与注意点
- JVM 并发模型或内存模型影响

## 6. 总结与思考
- 你从这部分代码中学到了什么？
- 是否有改进建议？
- 可以如何抽象、复用、推广？

## 7. 附录（可选）
- 类图 / 时序图
- 调用关系图 / 样例数据 / 执行截图
