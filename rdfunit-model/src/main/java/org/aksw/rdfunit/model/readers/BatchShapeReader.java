package org.aksw.rdfunit.model.readers;

import com.google.common.collect.ImmutableSet;
import org.aksw.rdfunit.model.interfaces.Shape;
import org.aksw.rdfunit.model.shacl.TemplateRegistry;
import org.aksw.rdfunit.vocabulary.SHACL;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.vocabulary.RDF;

import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Reader a set of Patterns
 *
 * @author Dimitris Kontokostas
 * @since 9/26/15 12:33 PM
 * @version $Id: $Id
 */
public final class BatchShapeReader {

    private final TemplateRegistry templateRegistry;

    private BatchShapeReader(TemplateRegistry templateRegistry){ this.templateRegistry = templateRegistry;}

    public static BatchShapeReader create(TemplateRegistry templateRegistry) { return new BatchShapeReader(templateRegistry);}

    public Set<Shape> getShapesFromModel(Model model) {
        ConcurrentLinkedQueue<Shape> shapes = new ConcurrentLinkedQueue<>();

        model.listResourcesWithProperty(RDF.type, SHACL.Shape).toList()
                .parallelStream()
                .forEach(resource -> shapes.add(ShapeReader.create(templateRegistry).read(resource)));

        return ImmutableSet.copyOf(shapes);

    }

}
