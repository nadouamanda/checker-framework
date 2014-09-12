package org.checkerframework.checker.experimental.tainting_qual_poly;

import java.lang.annotation.Annotation;
import java.util.*;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;

import org.checkerframework.javacutil.AnnotationUtils;

import org.checkerframework.qualframework.poly.QualifierParameterAnnotationConverter;
import org.checkerframework.qualframework.poly.CombiningOperation;
import org.checkerframework.qualframework.poly.PolyQual.GroundQual;
import org.checkerframework.qualframework.poly.PolyQual.QualVar;
import org.checkerframework.qualframework.poly.QualParams;
import org.checkerframework.qualframework.poly.Wildcard;

import org.checkerframework.checker.experimental.tainting_qual_poly.qual.*;

public class TaintingAnnotationConverter implements QualifierParameterAnnotationConverter<Tainting> {
    private final CombiningOperation<Tainting> lubOp;

    private static final Tainting BOTTOM = Tainting.UNTAINTED;
    private static final Tainting TOP = Tainting.TAINTED;

    private static final String MULTI_ANNO_NAME_PREFIX = MultiTainted.class.getPackage().getName() + ".Multi";
    private static final String DEFAULT_NAME = "Main";
    private static final String POLY_NAME = "_poly";

    public TaintingAnnotationConverter() {
        this.lubOp = new CombiningOperation.Lub<>(new TaintingQualifierHierarchy());
    }

    private void mergeParams(
            Map<String, Wildcard<Tainting>> params,
            Map<String, Wildcard<Tainting>> newParams) {
        if (newParams == null) {
            return;
        }

        for (String name : newParams.keySet()) {
            if (!params.containsKey(name)) {
                params.put(name, newParams.get(name));
                continue;
            }

            Wildcard<Tainting> oldWild = params.get(name);
            Wildcard<Tainting> newWild = newParams.get(name);
            Wildcard<Tainting> combinedWild = oldWild.combineWith(newWild, lubOp, lubOp);

            //System.err.printf("COMBINE[%s]: %s + %s = %s\n", name, oldWild, newWild, combinedWild);

            params.put(name, combinedWild);
        }
    }

    private Map<String, Wildcard<Tainting>> fromAnnotation(AnnotationMirror anno) {
        String name = AnnotationUtils.annotationName(anno);

        if (name.startsWith(MULTI_ANNO_NAME_PREFIX)) {
            Map<String, Wildcard<Tainting>> result = new HashMap<>();
            AnnotationMirror[] subAnnos = AnnotationUtils.getElementValue(
                    anno, "value", AnnotationMirror[].class, true);
            for (AnnotationMirror subAnno : subAnnos) {
                mergeParams(result, fromAnnotation(subAnno));
            }
            return result;

        } else if (name.equals(Tainted.class.getName())) {
            String target = AnnotationUtils.getElementValue(anno, "target", String.class, true);
            return Collections.singletonMap(target, new Wildcard<>(Tainting.TAINTED));

        } else if (name.equals(Untainted.class.getName())) {
            String target = AnnotationUtils.getElementValue(anno, "target", String.class, true);
            return Collections.singletonMap(target, new Wildcard<>(Tainting.UNTAINTED));

        } else if (name.equals(Var.class.getName())) {
            String target = AnnotationUtils.getElementValue(anno, "target", String.class, true);
            String value = AnnotationUtils.getElementValue(anno, "value", String.class, true);
            Wildcard<Tainting> valueWild = new Wildcard<>(
                    new QualVar<>(value, BOTTOM, TOP));
            return Collections.singletonMap(target, valueWild);

        } else if (name.equals(PolyTainting.class.getName())) {
            String target = AnnotationUtils.getElementValue(anno, "target", String.class, true);
            Wildcard<Tainting> polyWild = new Wildcard<>(
                    new QualVar<>(POLY_NAME, BOTTOM, TOP));
            return Collections.singletonMap(target, polyWild);

        } else if (name.equals(Wild.class.getName())) {
            String target = AnnotationUtils.getElementValue(anno, "target", String.class, true);
            return Collections.singletonMap(target, new Wildcard<>(BOTTOM, TOP));

        }

        return null;
    }

    private boolean handleExtendsSuper(AnnotationMirror anno, Map<String, Wildcard<Tainting>> params) {
        String name = AnnotationUtils.annotationName(anno);

        if (name.startsWith(MULTI_ANNO_NAME_PREFIX)) {
            AnnotationMirror[] subAnnos = AnnotationUtils.getElementValue(
                    anno, "value", AnnotationMirror[].class, true);
            for (AnnotationMirror subAnno : subAnnos) {
                handleExtendsSuper(subAnno, params);
            }
            return (subAnnos.length > 0);

        } else if (name.equals(Extends.class.getName())) {
            String target = AnnotationUtils.getElementValue(anno, "target", String.class, true);
            Wildcard<Tainting> oldWild = params.get(target);
            Wildcard<Tainting> newWild = new Wildcard<>(new GroundQual<>(BOTTOM), oldWild.getUpperBound());
            params.put(target, newWild);
            return true;

        } else if (name.equals(Super.class.getName())) {
            String target = AnnotationUtils.getElementValue(anno, "target", String.class, true);
            Wildcard<Tainting> oldWild = params.get(target);
            Wildcard<Tainting> newWild = new Wildcard<>(oldWild.getLowerBound(), new GroundQual<>(TOP));
            params.put(target, newWild);
            return true;

        }

        return false;
    }

    @Override
    public QualParams<Tainting> fromAnnotations(Collection<? extends AnnotationMirror> annos) {
        Map<String, Wildcard<Tainting>> params = new HashMap<>();
        for (AnnotationMirror anno : annos) {
            mergeParams(params, fromAnnotation(anno));
        }
        for (AnnotationMirror anno : annos) {
            handleExtendsSuper(anno, params);
        }
        return (params.isEmpty() ? null : new QualParams<>(params));
    }

    @Override
    public boolean isAnnotationSupported(AnnotationMirror anno) {
        String name = AnnotationUtils.annotationName(anno);
        // Avoid running fromAnnotation on Multi* annotations, since that could
        // involve a nontrivial amount of work.
        return name.startsWith(MULTI_ANNO_NAME_PREFIX)
            || name.equals(Extends.class.getName())
            || name.equals(Super.class.getName())
            || fromAnnotation(anno) != null;
    }

    @Override
    public Set<String> getDeclaredParameters(Element elt) {
        Set<String> result = new HashSet<>();
        for (Annotation a : elt.getAnnotationsByType(TaintingParam.class)) {
            result.add(((TaintingParam)a).value());
        }

        switch (elt.getKind()) {
            case CLASS:
            case INTERFACE:
            case ENUM:
                result.add(DEFAULT_NAME);
                break;
            case CONSTRUCTOR:
            case METHOD:
                if (hasPolyAnnotation((ExecutableElement)elt)) {
                    result.add(POLY_NAME);
                }
                break;
            default:
                break;
        }

        return result;
    }

    private boolean hasPolyAnnotation(ExecutableElement elt) {
        if (hasPolyAnnotation(elt.getReturnType())) {
            return true;
        }
        if (hasPolyAnnotation(elt.getReceiverType())) {
            return true;
        }
        for (VariableElement paramElt : elt.getParameters()) {
            if (hasPolyAnnotation(paramElt.asType())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasPolyAnnotation(TypeMirror type) {
        if (type == null) {
            return false;
        }
        for (AnnotationMirror anno : type.getAnnotationMirrors()) {
            if (AnnotationUtils.annotationName(anno).equals(PolyTainting.class.getName())) {
                return true;
            }
        }
        return false;
    }
}