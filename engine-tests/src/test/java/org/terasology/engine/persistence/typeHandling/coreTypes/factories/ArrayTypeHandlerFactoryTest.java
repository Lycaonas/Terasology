// Copyright 2020 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.engine.persistence.typeHandling.coreTypes.factories;

import org.junit.jupiter.api.Test;
import org.terasology.engine.persistence.typeHandling.TypeHandler;
import org.terasology.engine.persistence.typeHandling.TypeHandlerContext;
import org.terasology.engine.persistence.typeHandling.TypeHandlerLibrary;
import org.terasology.engine.persistence.typeHandling.coreTypes.ArrayTypeHandler;
import org.terasology.engine.persistence.typeHandling.reflection.SerializationSandbox;
import org.terasology.nui.reflection.TypeInfo;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class ArrayTypeHandlerFactoryTest {
    private final TypeHandlerLibrary typeHandlerLibrary = mock(TypeHandlerLibrary.class);
    private final ArrayTypeHandlerFactory typeHandlerFactory = new ArrayTypeHandlerFactory();
    private final TypeHandlerContext context =
            new TypeHandlerContext(typeHandlerLibrary, mock(SerializationSandbox.class));

    @Test
    public void testArray() {
        TypeInfo<int[]> arrayTypeInfo = TypeInfo.of(int[].class);

        Optional<TypeHandler<int[]>> typeHandler =
                typeHandlerFactory.create(arrayTypeInfo, context);

        assertTrue(typeHandler.isPresent());
        assertTrue(typeHandler.get() instanceof ArrayTypeHandler);

        // Verify that the Integer TypeHandler was loaded from the TypeHandlerLibrary
        verify(typeHandlerLibrary).getTypeHandler(eq(TypeInfo.of(int.class).getType()));
    }

    @Test
    public void testGenericArray() {
        TypeInfo<List<Integer>[]> arrayTypeInfo = new TypeInfo<List<Integer>[]>() {};

        Optional<TypeHandler<List<Integer>[]>> typeHandler =
                typeHandlerFactory.create(arrayTypeInfo, context);

        assertTrue(typeHandler.isPresent());
        assertTrue(typeHandler.get() instanceof ArrayTypeHandler);

        // Verify that the List<Integer> TypeHandler was loaded from the TypeHandlerLibrary
        verify(typeHandlerLibrary).getTypeHandler(eq(new TypeInfo<List<Integer>>() {}.getType()));
    }

    @Test
    public void testNonArray() {
        TypeInfo<List<Integer>> arrayTypeInfo = new TypeInfo<List<Integer>>() {};

        Optional<TypeHandler<List<Integer>>> typeHandler =
                typeHandlerFactory.create(arrayTypeInfo, context);

        assertFalse(typeHandler.isPresent());
    }
}