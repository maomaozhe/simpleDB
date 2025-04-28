package top.guoziyang.mydb.backend.tm;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import top.guoziyang.mydb.backend.utils.Panic;
import top.guoziyang.mydb.backend.utils.Parser;
import top.guoziyang.mydb.common.Error;

public class TransactionManagerImpl implements TransactionManager {

    // XID文件头长度, 八个字节的数字
    static final int LEN_XID_HEADER_LENGTH = 8;
    // 每个事务的占用长度
    private static final int XID_FIELD_SIZE = 1;

    // 事务的三种状态
    private static final byte FIELD_TRAN_ACTIVE   = 0;
	private static final byte FIELD_TRAN_COMMITTED = 1;
	private static final byte FIELD_TRAN_ABORTED  = 2;

    // 超级事务，永远为commited状态
    public static final long SUPER_XID = 0;

    //W文件后缀

    static final String XID_SUFFIX = ".xid";
    
    private RandomAccessFile file;


    //NIO方式的FileChannel 和传统I/O的stream有一些区别
    private FileChannel fc;
    private long xidCounter;

    //锁？
    private Lock counterLock;

    TransactionManagerImpl(RandomAccessFile raf, FileChannel fc) {

        //构造方法
        this.file = raf;
        this.fc = fc;

        counterLock = new ReentrantLock();
        checkXIDCounter();


    }

    /**
     * 检查XID文件是否合法
     * 读取XID_FILE_HEADER中的xidcounter，根据它计算文件的理论长度，对比实际长度
     * 通过文件头的 8 字节数字反推文件的理论长度，与文件的实际长度做对比。
     */
    private void checkXIDCounter() {
        long fileLen = 0;
        try{
            fileLen = file.length();
        } catch (IOException e1){
            Panic.panic(Error.BadXIDFileException);
        }

        if(fileLen < LEN_XID_HEADER_LENGTH) {
            Panic.panic(Error.BadXIDFileException);
        }

        ByteBuffer buffer = ByteBuffer.allocate(LEN_XID_HEADER_LENGTH);
        try{
            //FileChannel
            fc.position(0);
            fc.read(buffer);

        }catch (IOException e){
            Panic.panic(e);
        }

        this.xidCounter = Parser.parseLong(buffer.array());
        long end = getXidPosition(this.xidCounter + 1);
        //校验没有通过的，会直接通过 panic 方法，强制停机
        if(end != fileLen) {
            Panic.panic(Error.BadXIDFileException);
        }


    }


    // 根据事务xid取得其在xid文件中对应的位置
    private long getXidPosition(long xid) {
        return LEN_XID_HEADER_LENGTH + (xid-1)*XID_FIELD_SIZE;
    }

    // 更新xid事务的状态为status
    private void updateXID(long xid, byte status) {


        //找到偏移
        long offset = getXidPosition(xid);
        byte[] tmp = new byte[XID_FIELD_SIZE];
        tmp[0] = status;
        ByteBuffer buffer = ByteBuffer.wrap(tmp);

        try{
            fc.position(offset);
            fc.write(buffer);
        }catch(IOException e){
            Panic.panic(e);
        }

        try{
            fc.force(false);
        }catch(IOException e){
            Panic.panic(e);
        }


    }

    // 将XID加一，并更新XID Header
    private void incrXIDCounter() {

        xidCounter++;
        //
        ByteBuffer buffer = ByteBuffer.wrap(Parser.long2Byte(xidCounter));
        try{
            fc.position(0);
            fc.write(buffer);
        }catch(IOException e){
            Panic.panic(e);
        }

        try{
            //filechannel focrce 强制同步缓存到文件
            //方法参数是一个布尔，表示是否同步元数据
            fc.force(false);
        } catch(IOException e){
            Panic.panic(e);
        }

    }

    // 开始一个事务，并返回XID
    public long begin() {
        //加锁 保证线程安全
        counterLock.lock();
        try{
            long xid = xidCounter + 1;
            updateXID(xid, FIELD_TRAN_ACTIVE);
            incrXIDCounter();
            return xid;


        } finally {
            counterLock.unlock();
        }

    }

    // 提交XID事务
    public void commit(long xid) {
        updateXID(xid, FIELD_TRAN_COMMITTED);
    }

    // 回滚XID事务
    public void abort(long xid) {
        updateXID(xid, FIELD_TRAN_ABORTED);
    }

    // 检测XID事务是否处于status状态

    //需要从磁盘获取数据？比对返回？
    private boolean checkXID(long xid, byte status) {
        long offset = getXidPosition(xid);
        ByteBuffer buf = ByteBuffer.wrap(new byte[XID_FIELD_SIZE]);
        try{
            fc.position(offset);
            fc.read(buf);
        }catch(IOException e){
            Panic.panic(e);
        }

        return buf.array()[0] == status;


    }

    public boolean isActive(long xid) {
        if(xid == SUPER_XID) return false;
        return checkXID(xid, FIELD_TRAN_ACTIVE);
    }

    public boolean isCommitted(long xid) {
        if(xid == SUPER_XID) return true;
        return checkXID(xid, FIELD_TRAN_COMMITTED);
    }

    public boolean isAborted(long xid) {
        if(xid == SUPER_XID) return false;
        return checkXID(xid, FIELD_TRAN_ABORTED);
    }

    public void close() {
        try {
            fc.close();
            file.close();
        } catch (IOException e) {
            Panic.panic(e);
        }
    }

}
