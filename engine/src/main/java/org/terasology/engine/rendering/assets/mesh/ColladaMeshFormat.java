// Copyright 2020 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.engine.rendering.assets.mesh;

import gnu.trove.list.TFloatList;
import gnu.trove.list.TIntList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terasology.gestalt.assets.ResourceUrn;
import org.terasology.gestalt.assets.format.AbstractAssetFileFormat;
import org.terasology.gestalt.assets.format.AssetDataFile;
import org.terasology.gestalt.assets.module.annotations.RegisterAssetFileFormat;
import org.terasology.engine.rendering.collada.ColladaLoader;
import org.terasology.engine.rendering.collada.ColladaParseException;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Importer for Collada data exchange model files.  Supports mesh data
 * <p>
 * The development of this loader was greatly influenced by
 * http://www.wazim.com/Collada_Tutorial_1.htm
 *
 */

@RegisterAssetFileFormat
public class ColladaMeshFormat extends AbstractAssetFileFormat<MeshData> {

    private static final Logger logger = LoggerFactory.getLogger(ColladaMeshFormat.class);

    public ColladaMeshFormat() {
        super("dae");
    }

    @Override
    public MeshData load(ResourceUrn urn, List<AssetDataFile> inputs) throws IOException {
        logger.info("Loading mesh for " + urn);

        ColladaLoader loader = new ColladaLoader();

        try (InputStream stream = inputs.get(0).openStream()) {
            loader.parseMeshData(stream);
        } catch (ColladaParseException e) {
            throw new IOException("Error loading collada mesh for " + urn, e);
        }

        MeshData data = new MeshData();
        TFloatList colorsMesh = data.getColors();
        TFloatList verticesMesh = data.getVertices();
        TFloatList texCoord0Mesh = data.getTexCoord0();
        TFloatList normalsMesh = data.getNormals();
        TIntList indicesMesh = data.getIndices();

        // Scale vertices coordinates by unitsPerMeter
        for (int i = 0; i < loader.getVertices().size(); i++) {
            float originalVertexValue = loader.getVertices().get(i);
            float adjustedVertexValue = (float) (originalVertexValue * loader.getUnitsPerMeter());
            verticesMesh.add(adjustedVertexValue);
        }

        colorsMesh.addAll(loader.getColors());
        texCoord0Mesh.addAll(loader.getTexCoord0());
        normalsMesh.addAll(loader.getNormals());
        indicesMesh.addAll(loader.getIndices());

        if (data.getVertices() == null) {
            throw new IOException("No vertices define");
        }
        //if (data.getNormals() == null || data.getNormals().size() != data.getVertices().size()) {
        //    throw new IOException("The number of normals does not match the number of vertices.");
        //}

        if (((null == data.getColors()) || (0 == data.getColors().size()))
                && ((null == data.getTexCoord0()) || (0 == data.getTexCoord0().size()))) {
            throw new IOException("There must be either texture coordinates or vertex colors provided.");
        }

        if ((null != data.getTexCoord0()) && (0 != data.getTexCoord0().size())) {
            if (data.getTexCoord0().size() / 2 != data.getVertices().size() / 3) {
                throw new IOException("The number of tex coords (" + data.getTexCoord0().size() / 2
                        + ") does not match the number of vertices (" + data.getVertices().size() / 3
                        + ").");
            }
        }

        if ((null != data.getColors()) && (0 != data.getColors().size())) {
            if (data.getColors().size() / 4 != data.getVertices().size() / 3) {
                throw new IOException("The number of vertex colors (" + data.getColors().size() / 4
                        + ") does not match the number of vertices (" + data.getVertices().size() / 3
                        + ").");
            }
        }

        return data;
    }

}