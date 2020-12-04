package com.ivianuu.injekt.integrationtests

import com.ivianuu.injekt.test.codegen
import org.junit.Test

class IndexTest {

    @Test
    fun testCanIndexDeclarationsWithTheSameName() = codegen(
        """
            @Binding
            val foo get() = Foo()
            
            @Binding
            fun foo() = Foo()
        """
    )

}