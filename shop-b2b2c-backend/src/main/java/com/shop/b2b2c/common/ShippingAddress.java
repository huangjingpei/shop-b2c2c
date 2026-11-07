/*
 * 文件：ShippingAddress.java
 * 用途：F07 实物收货地址的校验与订单快照写入规则。
 * 维护注意：
 *  1. 仅 goodsType=1 的实物订单需要收货地址；卡密/定制服务订单不写入任何收件人字段，避免数字商品被要求填写地址。
 *  2. 地址是下单时快照，后续修改店铺资料或买家资料不得回写历史订单。
 *  3. 收件手机号只用于物流面单，不替代 buyerContact 的免密查单凭据；接口响应仍按买家白名单输出。
 *  4. 校验失败抛出 BusinessException(400)，不拼接原始地址到错误消息，避免日志泄露。
 */
package com.shop.b2b2c.common;

import com.shop.b2b2c.entity.TradeOrder;

import java.util.regex.Pattern;

public final class ShippingAddress {

    private static final Pattern MOBILE = Pattern.compile("^1[3-9]\\d{9}$");

    private ShippingAddress() {
    }

    /** 已校验的地址快照；所有字段均去除首尾空白。 */
    public record Snapshot(String receiverName, String receiverPhone,
                           String receiverRegion, String receiverAddress) {
    }

    public static boolean requiredForGoodsType(Integer goodsType) {
        return Integer.valueOf(1).equals(goodsType);
    }

    /**
     * 实物订单必填校验：姓名、手机号、省市区、详细地址四项齐全才允许建单。
     * 非实物订单直接返回 null，不读取也不保存任何地址字段。
     */
    public static Snapshot require(Integer goodsType, String name, String phone, String region, String address) {
        if (!requiredForGoodsType(goodsType)) {
            return null;
        }
        return validate(name, phone, region, address);
    }

    /**
     * 可选地址校验（商家代客推单）：四项全空视为未填写；任一项非空则四项必须合法齐全，
     * 避免产生看似有地址却无法发货的半截快照。
     */
    public static Snapshot optional(Integer goodsType, String name, String phone, String region, String address) {
        if (!requiredForGoodsType(goodsType)) {
            return null;
        }
        if (isBlank(name) && isBlank(phone) && isBlank(region) && isBlank(address)) {
            return null;
        }
        return validate(name, phone, region, address);
    }

    public static void apply(TradeOrder order, Snapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        order.setReceiverName(snapshot.receiverName());
        order.setReceiverPhone(snapshot.receiverPhone());
        order.setReceiverRegion(snapshot.receiverRegion());
        order.setReceiverAddress(snapshot.receiverAddress());
    }

    private static Snapshot validate(String name, String phone, String region, String address) {
        String receiverName = trim(name);
        if (isBlank(receiverName)) {
            throw new BusinessException(400, "请填写收货人姓名");
        }
        if (receiverName.length() > 32) {
            throw new BusinessException(400, "收货人姓名长度不能超过32字符");
        }
        String receiverPhone = trim(phone);
        if (isBlank(receiverPhone)) {
            throw new BusinessException(400, "请填写收货人手机号");
        }
        if (!MOBILE.matcher(receiverPhone).matches()) {
            throw new BusinessException(400, "收货人手机号格式不正确");
        }
        String receiverRegion = trim(region);
        if (isBlank(receiverRegion)) {
            throw new BusinessException(400, "请填写收货省市区");
        }
        if (receiverRegion.length() > 64) {
            throw new BusinessException(400, "收货省市区长度不能超过64字符");
        }
        String receiverAddress = trim(address);
        if (isBlank(receiverAddress)) {
            throw new BusinessException(400, "请填写详细收货地址");
        }
        if (receiverAddress.length() < 5 || receiverAddress.length() > 200) {
            throw new BusinessException(400, "详细收货地址长度须在5到200字符之间");
        }
        return new Snapshot(receiverName, receiverPhone, receiverRegion, receiverAddress);
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
