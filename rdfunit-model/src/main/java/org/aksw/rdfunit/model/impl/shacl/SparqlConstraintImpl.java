package org.aksw.rdfunit.model.impl.shacl;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import lombok.Value;
import org.aksw.rdfunit.enums.RLOGLevel;
import org.aksw.rdfunit.enums.TestAppliesTo;
import org.aksw.rdfunit.enums.TestGenerationType;
import org.aksw.rdfunit.model.impl.ManualTestCaseImpl;
import org.aksw.rdfunit.model.impl.ResultAnnotationImpl;
import org.aksw.rdfunit.model.interfaces.ResultAnnotation;
import org.aksw.rdfunit.model.interfaces.TestCase;
import org.aksw.rdfunit.model.interfaces.TestCaseAnnotation;
import org.aksw.rdfunit.model.interfaces.shacl.Shape;
import org.aksw.rdfunit.model.interfaces.shacl.SparqlConstraint;
import org.aksw.rdfunit.model.interfaces.shacl.Validator;
import org.aksw.rdfunit.utils.JenaUtils;
import org.aksw.rdfunit.vocabulary.SHACL;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;

import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Builder
@Value
public class SparqlConstraintImpl implements SparqlConstraint {
    @Getter @NonNull private final Shape shape;
    @Getter @NonNull private final Literal message;
    @Getter @NonNull private final Resource severity;
    @NonNull private final Validator validator;

    @Override
    public TestCase getTestCase() {

        validateSparqlQuery(validator.getSparqlQuery());

        ManualTestCaseImpl.ManualTestCaseImplBuilder testBuilder = ManualTestCaseImpl.builder();
        String sparql;
        sparql = generateSparqlWhere(validator.getSparqlQuery());


        return testBuilder
                .element(createTestCaseResource())
                .sparqlPrevalence("")
                .sparqlWhere(sparql)
                .prefixDeclarations(validator.getPrefixDeclarations())
                .testCaseAnnotation(generateTestAnnotations(validator.getSparqlQuery()))
                .build();
    }

    protected static void validateSparqlQuery(String sparqlQuery) {
        String originalSparqlQueryLowerCase =  sparqlQuery.toUpperCase();
        if (
                        originalSparqlQueryLowerCase.contains("VALUES") ||
                        originalSparqlQueryLowerCase.contains("SERVICE") ||
                        originalSparqlQueryLowerCase.contains("MINUS") ) {
            throw new IllegalArgumentException("Pre-binding failed in query because of illegal constructs (VALUES, SERVICE, MINUS):\n" + sparqlQuery);
        }

        ImmutableSet<String> preboundVars = ImmutableSet.of("this", "shapesGraph", "currentShape", "value");
        preboundVars.forEach( var ->{
            if (sparqlQuery.matches("(?s).*[Aa][Ss]\\s+[\\?\\$]" + var + "\\W.*")) {
                throw new IllegalArgumentException("Pre-binding failed in query because of use of pre-bind variable with AS:\n" + sparqlQuery);
            }
        });

    }

    private String generateSparqlWhere(String sparqlString) {

        String  sparqlWhere = sparqlString
                .substring(sparqlString.indexOf('{'));
        if (shape.getPath().isPresent()) {
                sparqlWhere = sparqlWhere.replaceAll(Pattern.quote("$PATH"), Matcher.quoteReplacement(shape.getPath().get().asSparqlPropertyPath()));
        }
        return replaceBindings(sparqlWhere);
    }

    private String replaceBindings(String sparqlSnippet) {
        String bindedSnippet = sparqlSnippet;
        if (shape.isPropertyShape()) {
            bindedSnippet = bindedSnippet.replaceAll(Pattern.quote("$PATH"), Matcher.quoteReplacement(shape.getPath().get().asSparqlPropertyPath()));
        }
        return bindedSnippet;
    }


    private Literal generateMessage() {

        if (shape.getMessage().isPresent()) {
            return shape.getMessage().get();
        } else {
            String messageLanguage = message.getLanguage();
            String message = replaceBindings(this.message.getLexicalForm());
            if (messageLanguage == null || messageLanguage.isEmpty()) {
                return ResourceFactory.createStringLiteral(message);
            } else {
                return ResourceFactory.createLangLiteral(message, messageLanguage);
            }
        }
    }

    // hack for now
    private TestCaseAnnotation generateTestAnnotations(String originalSelectQuery) {
        return new TestCaseAnnotation(
                createTestCaseResource(),
                TestGenerationType.AutoGenerated,
                null,
                TestAppliesTo.Schema, // TODO check
                SHACL.namespace,      // TODO check
                Collections.emptyList(),
                generateMessage().getLexicalForm(),
                RLOGLevel.ERROR, //FIXME
                createResultAnnotations(originalSelectQuery)
        );
    }

    private List<ResultAnnotation> createResultAnnotations(String originalSelectQuery) {
        ImmutableList.Builder<ResultAnnotation> annotations = ImmutableList.builder();

        String prefixes = validator.getPrefixDeclarations().stream()
                .map(p -> "PREFIX " + p.getPrefix() + ": <" + p.getNamespace() + ">")
                .collect(Collectors.joining("\n"));
        List<String> variableNames = QueryFactory.create(prefixes + originalSelectQuery).getResultVars();

        // add path
        if (variableNames.contains("path")) {
            annotations.add(new ResultAnnotationImpl.Builder(ResourceFactory.createResource(), SHACL.resultPath)
                    .setVariableName("path").build());
        } else {
            if (shape.getPath().isPresent()) {
                annotations.add(new ResultAnnotationImpl.Builder(ResourceFactory.createResource(), SHACL.resultPath)
                        .setValue(shape.getPath().get().getPathAsRdf()).build());
            }
        }

        if (variableNames.contains("message")) {
            annotations.add(new ResultAnnotationImpl.Builder(ResourceFactory.createResource(), SHACL.resultMessage)
                    .setVariableName("message").build());
        } else {
            if (validator.getDefaultMessage().isPresent()) {
                annotations.add(new ResultAnnotationImpl.Builder(ResourceFactory.createResource(), SHACL.resultMessage)
                        .setValue(validator.getDefaultMessage().get()).build());
            } else {
                if (shape.getMessage().isPresent()) {
                    annotations.add(new ResultAnnotationImpl.Builder(ResourceFactory.createResource(), SHACL.resultMessage)
                            .setValue(shape.getMessage().get()).build());
                }
            }
        }

        annotations.add(new ResultAnnotationImpl.Builder(ResourceFactory.createResource(), SHACL.focusNode)
                .setVariableName("this").build());
        annotations.add(new ResultAnnotationImpl.Builder(ResourceFactory.createResource(), SHACL.value)
                .setVariableName("value").build());

        annotations.add(new ResultAnnotationImpl.Builder(ResourceFactory.createResource(), SHACL.resultSeverity)
                .setValue(shape.getSeverity()).build());
        annotations.add(new ResultAnnotationImpl.Builder(ResourceFactory.createResource(), SHACL.sourceShape)
                    .setValue(shape.getElement()).build());
        annotations.add(new ResultAnnotationImpl.Builder(ResourceFactory.createResource(), SHACL.sourceConstraintComponent)
                .setValue(SHACL.SPARQLConstraintComponent).build());
        annotations.add(new ResultAnnotationImpl.Builder(ResourceFactory.createResource(), SHACL.sourceConstraint)
                .setValue(validator.getElement()).build());

        return annotations.build();
    }

    private Resource createTestCaseResource() {
        // FIXME temporary solution until we decide how to build stable unique test uris
        return ResourceFactory.createProperty(JenaUtils.getUniqueIri());
    }
}