package com.leo.cartools;

import android.os.IBinder;
import android.os.Parcel;

import java.lang.reflect.Method;

/**
 * 车机车辆属性读写（ICarProperty）。
 *
 * 服务：fce_misc_service_hal_property
 * AIDL：com.fce.misc.proxy.hardware.property.ICarProperty
 * 事务码：4 = getProperty(propId, areaId)，5 = setProperty(CarPropertyValue)
 *
 * 实测该服务不校验调用者，非 root 的应用可直接读写。
 */
final class CarProp {

    private static final String SVC = "fce_misc_service_hal_property";
    private static final String DESC = "com.fce.misc.proxy.hardware.property.ICarProperty";
    private static final int TX_GET = 4;
    private static final int TX_SET = 5;

    private static IBinder sBinder;

    private CarProp() {
    }

    private static IBinder binder() {
        if (sBinder != null && sBinder.isBinderAlive()) {
            return sBinder;
        }
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method m = sm.getMethod("getService", String.class);
            Object o = m.invoke(null, SVC);
            if (o instanceof IBinder) {
                sBinder = (IBinder) o;
                return sBinder;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 读整型属性。
     *
     * @return 读到的值；服务不可用、属性不存在或解析失败时返回 null
     */
    static Integer readInt(int propId, int areaId) {
        IBinder b = binder();
        if (b == null) {
            return null;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESC);
            data.writeInt(propId);
            data.writeInt(areaId);
            if (!b.transact(TX_GET, data, reply, 0)) {
                return null;
            }
            reply.readException();
            reply.readInt();        // 标志位
            reply.readInt();        // propId
            reply.readInt();        // areaId
            reply.readInt();        // status
            reply.readLong();       // timestamp
            reply.readString();     // 值类型名
            reply.readInt();        // Parcel value type
            return reply.readInt();
        } catch (Throwable t) {
            sBinder = null;
            return null;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /**
     * 写整型属性。
     *
     * @return true 表示 Binder 调用成功且服务端未抛异常
     */
    static boolean writeInt(int propId, int areaId, int value) {
        IBinder b = binder();
        if (b == null) {
            return false;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESC);
            data.writeInt(1);                           // 标志位
            data.writeInt(propId);
            data.writeInt(areaId);
            data.writeInt(0);                           // status
            data.writeLong(System.currentTimeMillis());
            data.writeString("java.lang.Integer");
            data.writeInt(1);                           // Parcel.VAL_INTEGER
            data.writeInt(value);
            if (!b.transact(TX_SET, data, reply, 0)) {
                return false;
            }
            reply.readException();
            return true;
        } catch (Throwable t) {
            sBinder = null;
            return false;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }
}
