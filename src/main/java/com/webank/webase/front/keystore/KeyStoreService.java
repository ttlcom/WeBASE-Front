/**
 * Copyright 2012-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.webank.webase.front.keystore;

import com.alibaba.fastjson.JSON;
import com.webank.webase.front.base.AesUtils;
import com.webank.webase.front.base.BaseResponse;
import com.webank.webase.front.base.ConstantCode;
import com.webank.webase.front.base.Constants;
import com.webank.webase.front.base.exception.FrontException;
import com.webank.webase.front.keystore.KeyStoreInfo;
import com.webank.webase.front.util.CommonUtils;
import java.io.UnsupportedEncodingException;
import java.util.Base64;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.fisco.bcos.web3j.crypto.Credentials;
import org.fisco.bcos.web3j.crypto.ECKeyPair;
import org.fisco.bcos.web3j.crypto.Keys;
import org.fisco.bcos.web3j.utils.Numeric;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import org.springframework.web.client.RestTemplate;


/**
 * KeyStoreService.
 */
@Slf4j
@Service
public class KeyStoreService {

    @Autowired
    private AesUtils aesUtils;
    @Autowired
    Constants constants;
    @Autowired
    RestTemplate restTemplate;
    static final int PUBLIC_KEY_LENGTH_IN_HEX = 128;

    public static HashMap<String, String> keyMap = new HashMap();

    /**
     * createPrivateKey.
     */
    public KeyStoreInfo createPrivateKey(boolean useAes) {
        try {
            ECKeyPair keyPair = Keys.createEcKeyPair();
            return keyPair2KeyStoreInfo(keyPair, useAes);
        } catch (Exception e) {
            log.error("fail createPrivateKey.", e);
            throw new FrontException("create keyInfo failed");
        }
    }

    /**
     * get KeyStoreInfo by privateKey.
     */
    public KeyStoreInfo getKeyStoreFromPrivateKey(String privateKey, boolean useAes) {
        log.debug("start getKeyStoreFromPrivateKey. privateKey:{} useAes", privateKey, useAes);
        if (StringUtils.isBlank(privateKey)) {
            log.error("fail getKeyStoreFromPrivateKey. private key is null");
            throw new FrontException(ConstantCode.PRIVATEKEY_IS_NULL);
        }
        byte[] base64decodedBytes = Base64.getDecoder().decode(privateKey);
        String decodeKey = null;
        try {
            decodeKey = new String(base64decodedBytes, "utf-8");
        } catch (UnsupportedEncodingException e) {
            log.error("fail getKeyStoreFromPrivateKey", e);
            throw new FrontException(ConstantCode.PRIVATE_KEY_DECODE_FAIL);
        }
        ECKeyPair keyPair = ECKeyPair.create(Numeric.toBigInt(decodeKey));
        return keyPair2KeyStoreInfo(keyPair, useAes);
    }

    /**
     * convert ECKeyPair to KeyStoreInfo.
     */
    private KeyStoreInfo keyPair2KeyStoreInfo(ECKeyPair keyPair, boolean useAes) {

        String publicKey = Numeric
            .toHexStringWithPrefixZeroPadded(keyPair.getPublicKey(), PUBLIC_KEY_LENGTH_IN_HEX);
        String privateKey = Numeric.toHexStringNoPrefix(keyPair.getPrivateKey());
        String address = "0x" + Keys.getAddress(keyPair.getPublicKey());
        log.debug("publicKey:{} privateKey:{} address:{}", publicKey, privateKey, address);
        KeyStoreInfo keyStoreInfo = new KeyStoreInfo();
        keyStoreInfo.setPublicKey(publicKey);
        keyStoreInfo.setPrivateKey(privateKey);
        keyStoreInfo.setAddress(address);

        keyMap.put(address, privateKey);

        if (useAes) {
            keyStoreInfo.setPrivateKey(aesUtils.aesEncrypt(keyStoreInfo.getPrivateKey()));
        } else {
            keyStoreInfo.setPrivateKey(privateKey);
        }
        log.info("keyPair2KeyStoreInfo=======================keyMap:{}", JSON.toJSONString(keyMap));
        return keyStoreInfo;
    }


    /**
     * get credential.
     */
    public Credentials getCredentials(String user, boolean useAes) throws FrontException {
        String privateKey = Optional.ofNullable(getPrivateKey(user, useAes)).orElse(null);
        if (StringUtils.isBlank(privateKey)) {
            log.warn("fail getCredentials. user:{} privateKey is null", user);
            throw new FrontException(ConstantCode.PRIVATEKEY_IS_NULL);
        }
        return Credentials.create(privateKey);
    }


    /**
     * get PrivateKey.
     *
     * @param user userId or userAddress.
     */
    public String getPrivateKey(String user, boolean useAes) {
        log.info("getPrivateKey=======================keyMap:{}", JSON.toJSONString(keyMap));
        if (KeyStoreService.keyMap != null && KeyStoreService.keyMap.get(user) != null) {
            //get privateKey by address
            return KeyStoreService.keyMap.get(user);
        }

        //get privateKey by userId
        KeyStoreInfo keyStoreInfo = new KeyStoreInfo();
        String[] ipPortArr = constants.getMgrIpPorts().split(",");
        for (String ipPort : ipPortArr) {
            try {
                String url = String.format(Constants.MGR_PRIVATE_KEY_URI, ipPort, user);
                log.info("getPrivateKey url:{}", url);
                BaseResponse response = restTemplate.getForObject(url, BaseResponse.class);
                log.info("getPrivateKey response:{}", JSON.toJSONString(response));
                if (response.getCode() == 0) {
                    keyStoreInfo =
                        CommonUtils.object2JavaBean(response.getData(), KeyStoreInfo.class);
                    break;
                }
            } catch (Exception e) {
                log.warn("user:{} getPrivateKey from ipPort:{} exception", user, ipPort, e);
                continue;
            }
        }
        if (useAes) {
            return aesUtils.aesDecrypt(keyStoreInfo.getPrivateKey());
        }

        return keyStoreInfo.getPrivateKey();
    }
}

