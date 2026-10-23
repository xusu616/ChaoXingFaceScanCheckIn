package io.github.rd227.facescan

import org.junit.Assert.*
import org.junit.Test

class ReleaseVersionTest {
    @Test fun numericVersionsDoNotCompareLexicographically() {
        assertTrue(ReleaseVersion.compare("v1.10.0", "1.9.0")!! > 0)
        assertEquals(0, ReleaseVersion.compare("v1.2", "1.2.0"))
        assertTrue(ReleaseVersion.compare("1.0", "2.0")!! < 0)
    }

    @Test fun stableReleaseIsNewerThanCurrentExperimentalBuild() {
        assertTrue(ReleaseVersion.compare("v0.1.0", "0.1.0-experimental")!! > 0)
        assertTrue(ReleaseVersion.compare("1.0.0-rc.10", "1.0.0-rc.2")!! > 0)
        assertTrue(ReleaseVersion.compare("1.0.0-alpha", "1.0.0")!! < 0)
    }

    @Test fun metadataDoesNotMakeAnUpdateAndUnknownTagsAreNotInvented() {
        assertEquals(0, ReleaseVersion.compare("1.0.0+new", "1.0.0+old"))
        assertNull(ReleaseVersion.compare("latest", "0.1.0-experimental"))
        assertNull(ReleaseVersion.compare("v", "1.0"))
        assertTrue(ReleaseVersion.compare("999999999999999999999.0", "1.0")!! > 0)
    }
}
