/*
 * 文件：OrderCollectionModeSnapshotIntegrationTest.java
 * 用途：M4 下单收款模式快照的 H2 集成用例。
 * 维护注意：
 * 1. 覆盖普通直购、买家代付、卖家推单三条建单入口，验证快照取生效模式（应急关闭 > 总闸 > 商家配置）。
 * 2. 平台代收未启用或渠道绑定未就绪时必须 409 且不落订单，不得静默降级为商家直收。
 * 3. platformCollectEnabled 是进程级资质开关，用例内用反射临时打开并在 @AfterEach 复位；真实渠道联调仍由人工验证。
 */
package com.shop.b2b2c;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.common.CollectionMode;
import com.shop.b2b2c.controller.OrderController;
import com.shop.b2b2c.dto.PeerPayDTOs;
import com.shop.b2b2c.mapper.TradeOrderMapper;
import com.shop.b2b2c.service.PeerPayService;
import com.shop.b2b2c.service.PlatformCollectionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:order-collection-mode;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.sql.init.mode=always", "spring.sql.init.continue-on-error=false",
        "spring.sql.init.schema-locations=classpath:sprint1-schema.sql",
        "spring.sql.init.data-locations=",
        "mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl",
        "payment.mock=true", "inventory.expiry-delay-ms=3600000",
        "shop.poster.storage-dir=target/test-posters"
})
class OrderCollectionModeSnapshotIntegrationTest {

    @Autowired OrderController orderController;
    @Autowired PeerPayService peerPayService;
    @Autowired TradeOrderMapper orders;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformCollectionService platformCollectionService;
    @MockBean StringRedisTemplate redis;

    @BeforeEach
    void fixtures() {
        for (String table : List.of("inventory_reservation", "order_idempotency", "trade_order_item",
                "trade_order", "merchant_payment_account", "platform_collection_config",
                "merchant_collection_switch_audit", "product_virtual_card", "product_sku", "product",
                "merchant_store")) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("INSERT INTO merchant_store(id,store_code,store_name,owner_name,id_card_number,contact_phone) "
                + "VALUES(1,'S1','store1','owner','redacted','12345678')");
        jdbc.update("INSERT INTO product(id,merchant_id,category_id,title,goods_type,main_image,min_price) "
                + "VALUES(1,1,1,'goods',2,'image',10)");
        jdbc.update("INSERT INTO product_sku(id,merchant_id,product_id,sku_code,spec_data,price,stock_quantity) "
                + "VALUES(1,1,1,'SKU1','{}',10,5)");
    }

    @AfterEach
    void resetQualificationFlag() {
        ReflectionTestUtils.setField(platformCollectionService, "platformCollectEnabled", false);
    }

    @Test
    void checkoutSnapshotsMerchantDirectForDefaultStore() {
        orderController.checkout(checkout());

        assertEquals(CollectionMode.MERCHANT_DIRECT, orders.selectList(null).get(0).getCollectionMode());
    }

    @Test
    void gateClosedKeepsDirectSnapshotEvenWhenStoreConfiguredPlatformCollect() {
        jdbc.update("UPDATE merchant_store SET collection_mode='PLATFORM_COLLECT' WHERE id=1");

        orderController.checkout(checkout());

        assertEquals(CollectionMode.MERCHANT_DIRECT, orders.selectList(null).get(0).getCollectionMode());
    }

    @Test
    void platformConfiguredStoreIsRejectedBeforeQualificationChannelIsEnabled() {
        platformStoreWithGateOn();

        BusinessException error = assertThrows(BusinessException.class,
                () -> orderController.checkout(checkout()));

        assertEquals(409, error.getCode());
        assertTrue(error.getMessage().contains("分账通道未启用"));
        assertEquals(0, orders.selectCount(null));
    }

    @Test
    void platformConfiguredStoreIsRejectedWhenBindingIsNotReady() {
        platformStoreWithGateOn();
        ReflectionTestUtils.setField(platformCollectionService, "platformCollectEnabled", true);

        BusinessException error = assertThrows(BusinessException.class,
                () -> orderController.checkout(checkout()));

        assertEquals(409, error.getCode());
        assertTrue(error.getMessage().contains("平台代收渠道未就绪"));
        assertEquals(0, orders.selectCount(null));
    }

    @Test
    void readyPlatformCollectStoreSnapshotsPlatformCollect() {
        platformStoreWithGateOn();
        insertBoundAccount();
        ReflectionTestUtils.setField(platformCollectionService, "platformCollectEnabled", true);

        orderController.checkout(checkout());

        assertEquals(CollectionMode.PLATFORM_COLLECT, orders.selectList(null).get(0).getCollectionMode());
    }

    @Test
    void buyerPeerPayAlsoSnapshotsEffectiveCollectionMode() {
        platformStoreWithGateOn();
        insertBoundAccount();
        ReflectionTestUtils.setField(platformCollectionService, "platformCollectEnabled", true);

        PeerPayDTOs.BuyerCreateRequest request = new PeerPayDTOs.BuyerCreateRequest();
        request.setProductId(1L);
        request.setSkuId(1L);
        request.setQuantity(1);
        request.setBuyerContact("buyer@example.com");
        peerPayService.createBuyerPeerPay(request);

        assertEquals(CollectionMode.PLATFORM_COLLECT, orders.selectList(null).get(0).getCollectionMode());
    }

    private void platformStoreWithGateOn() {
        jdbc.update("UPDATE merchant_store SET collection_mode='PLATFORM_COLLECT' WHERE id=1");
        jdbc.update("INSERT INTO platform_collection_config(id,enabled,version) VALUES(1,1,0)");
    }

    private void insertBoundAccount() {
        jdbc.update("INSERT INTO merchant_payment_account(merchant_id,channel,sub_mchid,bank_account_name,"
                        + "bank_account_number,bank_name,apply_status,binding_status,settlement_account_id) "
                        + "VALUES(1,'WECHAT','MCH-1','fixture-name','redacted-card','fixture-bank','PASSED','BOUND',31)");
    }

    private OrderController.CheckoutDTO checkout() {
        OrderController.CheckoutDTO dto = new OrderController.CheckoutDTO();
        dto.setProductId(1L);
        dto.setSkuId(1L);
        dto.setQuantity(1);
        dto.setBuyerContact("buyer@example.com");
        return dto;
    }
}
