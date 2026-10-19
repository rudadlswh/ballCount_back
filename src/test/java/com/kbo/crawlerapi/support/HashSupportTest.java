package com.kbo.crawlerapi.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HashSupportTest {

    @Test
    void sha256HexUsesLowercaseHexOutput() {
        assertThat(HashSupport.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void sha256PrefixUsesByteLength() {
        assertThat(HashSupport.sha256Prefix("abc", 8)).isEqualTo("ba7816bf8f01cfea");
        assertThat(HashSupport.sha256BytePrefix("abc", 6)).isEqualTo("ba7816bf8f01");
    }

    @Test
    void sha256HexPrefixUsesHexLength() {
        assertThat(HashSupport.sha256HexPrefix("abc", 16)).isEqualTo("ba7816bf8f01cfea");
    }
}
