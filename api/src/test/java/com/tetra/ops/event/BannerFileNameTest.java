package com.tetra.ops.event;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 신청 내용의 bannerFileName — banner_image_path의 마지막 / 뒤. */
class BannerFileNameTest {

	@Test
	void lastSegmentOrNull() {
		assertThat(EventReader.fileName("assets/banners/1043/banner.png")).isEqualTo("banner.png");
		assertThat(EventReader.fileName("banner.jpg")).isEqualTo("banner.jpg");
		assertThat(EventReader.fileName(null)).isNull();
		assertThat(EventReader.fileName(" ")).isNull();
		assertThat(EventReader.fileName("assets/banners/1043/")).isNull();
	}

}
