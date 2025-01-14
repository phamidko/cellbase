/*
 * Copyright 2015-2020 OpenCB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.opencb.cellbase.lib.variant.hgvs;

import org.apache.commons.lang.NotImplementedException;
import org.apache.commons.lang3.StringUtils;
import org.opencb.biodata.models.core.Exon;
import org.opencb.biodata.models.core.Gene;
import org.opencb.biodata.models.core.Transcript;
import org.opencb.biodata.models.variant.Variant;
import org.opencb.biodata.tools.variant.VariantNormalizer;
import org.opencb.cellbase.core.exception.CellBaseException;
import org.opencb.cellbase.lib.managers.GenomeManager;
import org.opencb.cellbase.lib.variant.annotation.UnsupportedURLVariantFormat;
import org.opencb.cellbase.lib.variant.VariantAnnotationUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Created by fjlopez on 26/01/17.
 */
public class HgvsCalculator {

    protected static final char COLON = ':';
    protected static final String MT = "MT";
    private static final String CODING_TRANSCRIPT_CHAR = "c.";
    private static final String NON_CODING_TRANSCRIPT_CHAR = "n.";
    protected static final String PROTEIN_CHAR = "p.";
    protected static final char UNDERSCORE = '_';
    protected static final String POSITIVE = "+";
    protected static final String UNKNOWN_AMINOACID = "X";
    protected static Logger logger = LoggerFactory.getLogger(HgvsCalculator.class);
    protected static final int NEIGHBOURING_SEQUENCE_SIZE = 100;
    protected GenomeManager genomeManager;
    protected int dataRelease;
    protected BuildingComponents buildingComponents;
    private static final String VARIANT_STRING_PATTERN = "[ACGT]*";

    public HgvsCalculator(GenomeManager genomeManager, int dataRelease) {
        this.genomeManager = genomeManager;
        this.dataRelease = dataRelease;
    }

    // If allele is greater than this use allele length.
    private static final int MAX_ALLELE_LENGTH = 4;

    private static final VariantNormalizer NORMALIZER = new VariantNormalizer(false, false,
            false);


    public List<String> run(Variant variant, List<Gene> geneList) throws CellBaseException {
        return this.run(variant, geneList, true);
    }

    public List<String> run(Variant variant, List<Gene> geneList, boolean normalize) throws CellBaseException {
        List<String> hgvsList = new ArrayList<>();
        for (Gene gene : geneList) {
            hgvsList.addAll(this.run(variant, gene, normalize));
        }

        return hgvsList;
    }

    public List<String> run(Variant variant, Gene gene) throws CellBaseException {
        return run(variant, gene, true);
    }

    public List<String> run(Variant variant, Gene gene, boolean normalize) throws CellBaseException {
        if (gene.getTranscripts() == null) {
            return new ArrayList<>();
        }
        List<String> hgvsList = new ArrayList<>(gene.getTranscripts().size());
        for (Transcript transcript : gene.getTranscripts()) {
            hgvsList.addAll(this.run(variant, transcript, gene.getId(), normalize));
        }

        return hgvsList;
    }

    protected List<String> run(Variant variant, Transcript transcript, String geneId, boolean normalize) throws CellBaseException {
        List<String> hgvsStrings = new ArrayList<>();

        // Check variant falls within transcript coords
        if (variant.getChromosome().equals(transcript.getChromosome())
                && variant.getStart() <= transcript.getEnd() && variant.getEnd() >= transcript.getStart()) {
            // We cannot know the type of variant before normalization has been carried out
            Variant normalizedVariant = normalize(variant, normalize);

            HgvsTranscriptCalculator hgvsTranscriptCalculator = new HgvsTranscriptCalculator(genomeManager, dataRelease, normalizedVariant,
                    transcript, geneId);
            String hgvsTranscript = hgvsTranscriptCalculator.calculate();
            if (StringUtils.isNotEmpty(hgvsTranscript)) {
                hgvsStrings.add(hgvsTranscript);

                // Normalization set to false - if needed, it would have been done already two lines above
                //return hgvsCalculator.run(normalizedVariant, transcript, geneId, false);
                HgvsProteinCalculator hgvsProteinCalculator = new HgvsProteinCalculator(normalizedVariant, transcript);
                HgvsProtein hgvsProtein = hgvsProteinCalculator.calculate();
                if (hgvsProtein != null) {
                    for (String id : hgvsProtein.getIds()) {
                        String hgvsString = id + ":" + hgvsProtein.getHgvs();
                        hgvsStrings.add(hgvsString);
                    }
                }
            }
        }
        return hgvsStrings;
    }

    /**
     * Checks whether a variant is valid.
     *
     * @param variant Variant object to be checked.
     * @return   true/false depending on whether 'variant' does contain valid values. Currently just a simple check of
     * reference/alternate attributes being strings of [A,C,G,T] of length >= 0 is performed to detect cases such as
     * 19:13318673:(CAG)4:(CAG)5 which are not currently supported by CellBase. Ref and alt alleles must be different
     * as well for the variant to be valid. Functionality of the method may be improved in the future.
     */
    protected static boolean isValid(Variant variant) {
        return (variant.getReference().matches(VARIANT_STRING_PATTERN)
                && variant.getAlternate().matches(VARIANT_STRING_PATTERN)
                && !variant.getAlternate().equals(variant.getReference()));
    }

    protected Variant normalize(Variant variant, boolean normalize) {
        Variant normalizedVariant;
        // Convert VCF-style variant to HGVS-style.
        if (normalize) {
            List<Variant> normalizedVariantList = NORMALIZER.apply(Collections.singletonList(variant));
            if (normalizedVariantList != null && !normalizedVariantList.isEmpty()) {
                normalizedVariant = normalizedVariantList.get(0);
            } else {
                throw new UnsupportedURLVariantFormat("Variant " + variant.toString() + " cannot be properly normalized. "
                        + " Please check.");
            }
        } else {
            normalizedVariant = variant;
        }
        return normalizedVariant;
    }

    protected static boolean isCoding(Transcript transcript) {
        // 0 in the cdnaCodingEnd means that the transcript doesn't
        // have a coding end <==> is non coding. Just annotating
        // coding transcripts in a first approach
        return transcript.getCdnaCodingEnd() != 0;
    }

    protected void setRangeCoordsAndAlleles(int genomicStart, int genomicEnd, String genomicReference,
                                          String genomicAlternate, Transcript transcript,
                                          BuildingComponents buildingComponents) {
        int start;
        int end;
        String reference;
        String alternate;
        if ("+".equals(transcript.getStrand())) {
            start = genomicStart;
            // TODO: probably needs +-1 bp adjust
//            end = variant.getStart() + variant.getReferenceStart().length() - 1;
            end = genomicEnd;
            reference = genomicReference.length() > MAX_ALLELE_LENGTH
                    ? String.valueOf(genomicReference.length()) : genomicReference;
            alternate = genomicAlternate.length() > MAX_ALLELE_LENGTH
                    ? String.valueOf(genomicAlternate.length()) : genomicAlternate;
        } else {
            end = genomicStart;
            // TODO: probably needs +-1 bp adjust
            start = genomicEnd;
            reference = genomicReference.length() > MAX_ALLELE_LENGTH
                    ? String.valueOf(genomicReference.length())
                    : reverseComplementary(genomicReference);
            alternate = genomicAlternate.length() > MAX_ALLELE_LENGTH
                    ? String.valueOf(genomicAlternate.length())
                    : reverseComplementary(genomicAlternate);
        }
        buildingComponents.setReferenceStart(reference);
        buildingComponents.setAlternate(alternate);
        buildingComponents.setCdnaStart(genomicToCdnaCoord(transcript, start));
        buildingComponents.setCdnaEnd(genomicToCdnaCoord(transcript, end));
    }

    protected String reverseComplementary(String string) {
        StringBuilder stringBuilder = new StringBuilder(string).reverse();
        for (int i = 0; i < stringBuilder.length(); i++) {
            char nextNt = stringBuilder.charAt(i);
            // Protection against weird characters, e.g. alternate:"TBS" found in ClinVar
            if (VariantAnnotationUtils.COMPLEMENTARY_NT.containsKey(nextNt)) {
                stringBuilder.setCharAt(i, VariantAnnotationUtils.COMPLEMENTARY_NT.get(nextNt));
            } else {
                return null;
            }
        }
        return stringBuilder.toString();
    }

    /**
     * Justify an indel to the left or right along a sequence 'seq'.
     * @param variant Variant object that needs to be justified. It will get modified accordingly.
     * @param startOffset relative start position of the variant within genomicSequence (0-based).
     * @param endOffset relative end position of the variant within genomicSequence (0-based, startOffset=endOffset
     *                 for insertions).
     * @param allele String containing the allele that needs to be justified.
     * @param genomicSequence String containing the genomic sequence around the variant.getStart() position
     *                       (+-NEIGHBOURING_SEQUENCE_SIZE).
     * @param strand String {"+", "-"}.
     */
    protected void justify(Variant variant, int startOffset, int endOffset, String allele, String genomicSequence,
                         String strand) {
        StringBuilder stringBuilder = new StringBuilder(allele);
        // Justify to the left
        if ("-".equals(strand)) {
            while (startOffset > 0 && genomicSequence.charAt(startOffset - 1) == stringBuilder.charAt(stringBuilder.length() - 1)) {
                stringBuilder.deleteCharAt(stringBuilder.length() - 1);
                stringBuilder.insert(0, genomicSequence.charAt(startOffset - 1));
                startOffset--;
                endOffset--;
                variant.setStart(variant.getStart() - 1);
                variant.setEnd(variant.getEnd() - 1);
            }
        // Justify to the right
        } else {
            while ((endOffset + 1) < genomicSequence.length() && genomicSequence.charAt(endOffset + 1) == stringBuilder.charAt(0)) {
                stringBuilder.deleteCharAt(0);
                stringBuilder.append(genomicSequence.charAt(endOffset + 1));
                startOffset++;
                endOffset++;
                variant.setStart(variant.getStart() + 1);
                variant.setEnd(variant.getEnd() + 1);
            }
        }
        // Insertion
        if (variant.getReference().isEmpty()) {
            variant.setAlternate(stringBuilder.toString());
        // Deletion
        } else {
            variant.setReference(stringBuilder.toString());
        }
    }

    public static CdnaCoord genomicToCdnaCoord(Transcript transcript, int genomicPosition) {
        if (isCoding(transcript)) {
            return genomicToCdnaCoordInCodingTranscript(transcript, genomicPosition);
        } else {
            return genomicToCdnaCoordInNonCodingTranscript(transcript, genomicPosition);
        }

    }

    private static CdnaCoord genomicToCdnaCoordInNonCodingTranscript(Transcript transcript, int genomicPosition) {
        CdnaCoord cdnaCoord = new CdnaCoord();
        List<Exon> exonList = transcript.getExons();

        // Get the closest exon to the position, measured as the exon that presents the closest start OR end coordinate
        // to the position
        // Careful using GENOMIC coordinates
        Exon nearestExon = exonList.stream().min(Comparator.comparing(exon ->
                Math.min(Math.abs(genomicPosition - exon.getStart()),
                        Math.abs(genomicPosition - exon.getEnd())))).get();

        if (transcript.getStrand().equals("+")) {
            // Must now check which the closest edge of the exon is to the position: start or end to know which of them
            // to use as a reference
            // Careful using GENOMIC coordinates
            // Non-exonic variant: intronic
            // ------p------S||||||E------------; p = genomicPosition, S = nearestExon.getStart, E = nearestExon.getEnd
            if (genomicPosition - nearestExon.getStart() < 0) {
                // offset must be negative
                cdnaCoord.setOffset(genomicPosition - nearestExon.getStart()); // TODO: probably needs +-1 bp adjust
                cdnaCoord.setReferencePosition(getCdnaPosition(transcript, nearestExon.getStart()));
                cdnaCoord.setLandmark(CdnaCoord.Landmark.TRANSCRIPT_START);
            // Exonic variant
            // -------------S|||p||E------------; p = genomicPosition, S = nearestExon.getStart, E = nearestExon.getEnd
            } else if (genomicPosition - nearestExon.getEnd() < 0) {
                // no offset
                cdnaCoord.setReferencePosition(getCdnaPosition(transcript, genomicPosition));
                cdnaCoord.setLandmark(CdnaCoord.Landmark.TRANSCRIPT_START);
            // Non-exonic variant: intronic, intergenic
            // -------------S||||||E-----p------; p = genomicPosition, S = nearestExon.getStart, E = nearestExon.getEnd
            } else {
                // offset must be positive
                cdnaCoord.setOffset(genomicPosition - nearestExon.getEnd()); // TODO: probably needs +-1 bp adjust
                cdnaCoord.setReferencePosition(getCdnaPosition(transcript, nearestExon.getEnd()));
                cdnaCoord.setLandmark(CdnaCoord.Landmark.TRANSCRIPT_START);
            }
        } else {
            // Must now check which the closest edge of the exon is to the position: start or end to know which of them
            // to use as a reference
            // Careful using GENOMIC coordinates
            // Non-exonic variant: intronic, intergenic
            // ------p------E||||||S------------; p = genomicPosition, S = nearestExon.getStart, E = nearestExon.getEnd
            if (genomicPosition - nearestExon.getStart() < 0) {
                // offset must be positive
                cdnaCoord.setOffset(nearestExon.getStart() - genomicPosition); // TODO: probably needs +-1 bp adjust
                cdnaCoord.setReferencePosition(getCdnaPosition(transcript, nearestExon.getStart()));
                cdnaCoord.setLandmark(CdnaCoord.Landmark.TRANSCRIPT_START);
            // Exonic variant
            // -------------E|||p||S------------; p = genomicPosition, S = nearestExon.getStart, E = nearestExon.getEnd
            } else if (genomicPosition - nearestExon.getEnd() < 0) {
                // no offset
                cdnaCoord.setReferencePosition(getCdnaPosition(transcript, genomicPosition));
                cdnaCoord.setLandmark(CdnaCoord.Landmark.TRANSCRIPT_START);
            // Non-exonic variant: intronic, intergenic
            // -------------E||||||S-----p------; p = genomicPosition, S = nearestExon.getStart, E = nearestExon.getEnd
            } else {
                // offset must be negative
                cdnaCoord.setOffset(nearestExon.getEnd() - genomicPosition); // TODO: probably needs +-1 bp adjust
                cdnaCoord.setReferencePosition(getCdnaPosition(transcript, nearestExon.getEnd()));
                cdnaCoord.setLandmark(CdnaCoord.Landmark.TRANSCRIPT_START);
            }
        }

        return cdnaCoord;

    }

    private static CdnaCoord genomicToCdnaCoordInCodingTranscript(Transcript transcript, int genomicPosition) {
        CdnaCoord cdnaCoord = new CdnaCoord();
        List<Exon> exonList = transcript.getExons();

        // Get the closest exon to the position, measured as the exon that presents the closest start OR end coordinate
        // to the position
        // Careful using GENOMIC coordinates
        Exon nearestExon = exonList.stream().min(Comparator.comparing(exon ->
                Math.min(Math.abs(genomicPosition - exon.getStart()),
                        Math.abs(genomicPosition - exon.getEnd())))).get();

        if (transcript.getStrand().equals("+")) {
            // Must now check which the closest edge of the exon is to the position: start or end to know which of them
            // to use as a reference
            // Careful using GENOMIC coordinates
            // Non-exonic variant: intronic
            // ------p------S||||||E------------; p = genomicPosition, S = nearestExon.getStart, E = nearestExon.getEnd
            if (genomicPosition - nearestExon.getStart() < 0) {
                // Before coding start
                if (genomicPosition < transcript.getGenomicCodingStart())  {
                    cdnaCoord.setOffset(genomicPosition - nearestExon.getStart());
                    cdnaCoord.setReferencePosition(getCdnaPosition(transcript, nearestExon.getStart()) - transcript.getCdnaCodingStart());
                    cdnaCoord.setLandmark(CdnaCoord.Landmark.CDNA_START_CODON);
                // After coding end
                } else if (genomicPosition > transcript.getGenomicCodingEnd()) {
                    cdnaCoord.setOffset(genomicPosition - nearestExon.getStart());
                    cdnaCoord.setReferencePosition(getCdnaPosition(transcript, nearestExon.getStart()) - transcript.getCdnaCodingEnd());
                    cdnaCoord.setLandmark(CdnaCoord.Landmark.CDNA_STOP_CODON);
                // Within coding start and end
                } else {
                    // offset must be negative
                    cdnaCoord.setOffset(genomicPosition - nearestExon.getStart()); // TODO: probably needs +-1 bp adjust
                    cdnaCoord.setReferencePosition(nearestExon.getCdsStart());
                    cdnaCoord.setLandmark(CdnaCoord.Landmark.CDNA_START_CODON);
                }
            // Exonic variant
            // -------------S|||p||E------------; p = genomicPosition, S = nearestExon.getStart, E = nearestExon.getEnd
            } else if (genomicPosition - nearestExon.getEnd() <= 0) {
                // Before coding start
                if (genomicPosition < transcript.getGenomicCodingStart())  {
                    cdnaCoord.setOffset(getCdnaPosition(transcript, genomicPosition) - transcript.getCdnaCodingStart());
                    cdnaCoord.setLandmark(CdnaCoord.Landmark.CDNA_START_CODON);
                // After coding end
                } else if (genomicPosition > transcript.getGenomicCodingEnd()) {
                    cdnaCoord.setOffset(getCdnaPosition(transcript, genomicPosition) - transcript.getCdnaCodingEnd());
                    cdnaCoord.setLandmark(CdnaCoord.Landmark.CDNA_STOP_CODON);
                // Within coding start and end
                } else {
                    // no offset
                    cdnaCoord.setReferencePosition(nearestExon.getCdsStart() + (genomicPosition - nearestExon.getGenomicCodingStart()));
                    cdnaCoord.setLandmark(CdnaCoord.Landmark.CDNA_START_CODON);
                }
            // Non-exonic variant: intronic, intergenic
            // -------------S||||||E-----p------; p = genomicPosition, S = nearestExon.getStart, E = nearestExon.getEnd
            } else {
                // Before coding start
                if (genomicPosition < transcript.getGenomicCodingStart())  {
                    cdnaCoord.setOffset(genomicPosition - nearestExon.getEnd());
                    cdnaCoord.setReferencePosition(getCdnaPosition(transcript, nearestExon.getEnd()) - transcript.getCdnaCodingStart());
                    cdnaCoord.setLandmark(CdnaCoord.Landmark.CDNA_START_CODON);
                // After coding end
                } else if (genomicPosition > transcript.getGenomicCodingEnd()) {
                    cdnaCoord.setOffset(genomicPosition - nearestExon.getEnd());
                    cdnaCoord.setReferencePosition(getCdnaPosition(transcript, nearestExon.getEnd()) - transcript.getCdnaCodingEnd());
                    cdnaCoord.setLandmark(CdnaCoord.Landmark.CDNA_STOP_CODON);
                // Within coding start and end
                } else {
                    // offset must be positive
                    cdnaCoord.setOffset(genomicPosition - nearestExon.getEnd()); // TODO: probably needs +-1 bp adjust
                    cdnaCoord.setReferencePosition(nearestExon.getCdsEnd());
                    cdnaCoord.setLandmark(CdnaCoord.Landmark.CDNA_START_CODON);
                }
            }
        } else {
            // Must now check which the closest edge of the exon is to the position: start or end to know which of them
            // to use as a reference
            // Careful using GENOMIC coordinates
            // Non-exonic variant: intronic, intergenic
            // ------p------E||||||S------------; p = genomicPosition, S = nearestExon.getStart, E = nearestExon.getEnd
            if (genomicPosition - nearestExon.getStart() < 0) {
                // Before (genomic) coding start
                if (genomicPosition < transcript.getGenomicCodingStart())  {
                    cdnaCoord.setOffset(nearestExon.getStart() - genomicPosition);
                    cdnaCoord.setReferencePosition(transcript.getCdnaCodingEnd() - getCdnaPosition(transcript, nearestExon.getStart()));
                    cdnaCoord.setLandmark(CdnaCoord.Landmark.CDNA_STOP_CODON);
                // After (genomic) coding end
                } else if (genomicPosition > transcript.getGenomicCodingEnd()) {
                    cdnaCoord.setOffset(nearestExon.getStart() - genomicPosition);
                    cdnaCoord.setReferencePosition(getCdnaPosition(transcript, nearestExon.getStart()) - transcript.getCdnaCodingStart());
                    cdnaCoord.setLandmark(CdnaCoord.Landmark.CDNA_START_CODON);
                // Within coding start and end
                } else {
                    // offset must be positive
                    cdnaCoord.setOffset(nearestExon.getStart() - genomicPosition); // TODO: probably needs +-1 bp adjust
                    cdnaCoord.setReferencePosition(nearestExon.getCdsEnd());
                    cdnaCoord.setLandmark(CdnaCoord.Landmark.CDNA_START_CODON);
                }
            // Exonic variant
            // -------------E|||p||S------------; p = genomicPosition, S = nearestExon.getStart, E = nearestExon.getEnd
            } else if (genomicPosition - nearestExon.getEnd() <= 0) {
                // Before (genomic) coding start
                if (genomicPosition < transcript.getGenomicCodingStart())  {
                    cdnaCoord.setOffset(getCdnaPosition(transcript, genomicPosition) - transcript.getCdnaCodingEnd());
                    cdnaCoord.setLandmark(CdnaCoord.Landmark.CDNA_STOP_CODON);
                // After (genomic) coding end
                } else if (genomicPosition > transcript.getGenomicCodingEnd()) {
                    cdnaCoord.setOffset(getCdnaPosition(transcript, genomicPosition) - transcript.getCdnaCodingStart());
                    cdnaCoord.setLandmark(CdnaCoord.Landmark.CDNA_START_CODON);
                // Within coding start and end
                } else {
                    // no offset
                    cdnaCoord.setReferencePosition(nearestExon.getCdsStart() + nearestExon.getGenomicCodingEnd() - genomicPosition);
                    cdnaCoord.setLandmark(CdnaCoord.Landmark.CDNA_START_CODON);
                }
            // Non-exonic variant: intronic, intergenic
            // -------------E||||||S-----p------; p = genomicPosition, S = nearestExon.getStart, E = nearestExon.getEnd
            } else {
                // Before (genomic) coding start
                if (genomicPosition < transcript.getGenomicCodingStart())  {
                    cdnaCoord.setOffset(nearestExon.getEnd() - genomicPosition);
                    cdnaCoord.setReferencePosition(getCdnaPosition(transcript, nearestExon.getEnd()) - transcript.getCdnaCodingEnd());
                    cdnaCoord.setLandmark(CdnaCoord.Landmark.CDNA_STOP_CODON);
                // After (genomic) coding end
                } else if (genomicPosition > transcript.getGenomicCodingEnd()) {
                    cdnaCoord.setOffset(nearestExon.getEnd() - genomicPosition);
                    cdnaCoord.setReferencePosition(getCdnaPosition(transcript, nearestExon.getEnd()) - transcript.getCdnaCodingStart());
                    cdnaCoord.setLandmark(CdnaCoord.Landmark.CDNA_START_CODON);
                // Within coding start and end
                } else {
                    // offset must be negative
                    cdnaCoord.setOffset(nearestExon.getEnd() - genomicPosition); // TODO: probably needs +-1 bp adjust
                    cdnaCoord.setReferencePosition(nearestExon.getCdsStart());
                    cdnaCoord.setLandmark(CdnaCoord.Landmark.CDNA_START_CODON);
                }
            }
        }

        return cdnaCoord;
    }

    private static int getCdnaPosition(Transcript transcript, int genomicPosition) {

        int i = 0;
        int cdnaPosition = 0;
        List<Exon> exonList = transcript.getExons();

        // Sum the part that corresponds to the exon where genomicPosition is located
        if ("+".equals(transcript.getStrand())) {
            while (i < exonList.size() && genomicPosition > exonList.get(i).getEnd()) {
                cdnaPosition += (exonList.get(i).getEnd() - exonList.get(i).getStart() + 1);
                i++;
            }
            return cdnaPosition + genomicPosition - exonList.get(i).getStart() + 1;
        } else {
            while (i < exonList.size() && genomicPosition < exonList.get(i).getStart()) {
                cdnaPosition += (exonList.get(i).getEnd() - exonList.get(i).getStart() + 1);
                i++;
            }
            return cdnaPosition + exonList.get(i).getEnd() - genomicPosition + 1;
        }

    }

    /**
     * Generate a protein HGVS string.
     * @param buildingComponents BuildingComponents object containing all elements needed to build the hgvs string
     * @return String containing an HGVS formatted variant representation
     */
    protected String formatProteinString(BuildingComponents buildingComponents) {
        return null;
    }

    /**
     * Generate a transcript HGVS string.
     * @param buildingComponents BuildingComponents object containing all elements needed to build the hgvs string
     * @return String containing an HGVS formatted variant representation
     */
    protected String formatTranscriptString(BuildingComponents buildingComponents) {

        StringBuilder allele = new StringBuilder();
        allele.append(formatPrefix(buildingComponents));  // if use_prefix else ''
        allele.append(COLON);

        if (buildingComponents.getKind().equals(BuildingComponents.Kind.CODING)) {
            allele.append(CODING_TRANSCRIPT_CHAR).append(formatCdnaCoords(buildingComponents)
                    + formatDnaAllele(buildingComponents));
        } else if (buildingComponents.getKind().equals(BuildingComponents.Kind.NON_CODING)) {
            allele.append(NON_CODING_TRANSCRIPT_CHAR).append(formatCdnaCoords(buildingComponents)
                    + formatDnaAllele(buildingComponents));
        } else {
            throw new NotImplementedException("HGVS calculation not implemented for variant "
                    + buildingComponents.getChromosome() + ":"
                    + buildingComponents.getStart() + ":" + buildingComponents.getReferenceStart() + ":"
                    + buildingComponents.getAlternate() + "; kind: " + buildingComponents.getKind());
        }

        return allele.toString();

    }

    protected String formatDnaAllele(BuildingComponents buildingComponents) {
        return null;
    }

    protected boolean onlySpansCodingSequence(Variant variant, Transcript transcript) {
        if (buildingComponents.getCdnaStart().getOffset() == 0  // Start falls within coding exon
                && buildingComponents.getCdnaEnd().getOffset() == 0) { // End falls within coding exon

            List<Exon> exonList = transcript.getExons();
            // Get the closest exon to the variant start, measured as the exon that presents the closest start OR end
            // coordinate to the position
            Exon nearestExon = exonList.stream().min(Comparator.comparing(exon ->
                    Math.min(Math.abs(variant.getStart() - exon.getStart()),
                            Math.abs(variant.getStart() - exon.getEnd())))).get();

            // Check if the same exon contains the variant end
            return variant.getEnd() >= nearestExon.getStart() && variant.getEnd() <= nearestExon.getEnd();

        }
        return false;
    }

//    protected static int getFirstCdsPhase(Transcript transcript) {
//        if (transcript.getStrand().equals(POSITIVE)) {
//            return transcript.getExons().get(0).getPhase();
//        } else {
//            return transcript.getExons().get(transcript.getExons().size() - 1).getPhase();
//        }
//    }

    protected static int getAminoAcidPosition(int cdsPosition, Transcript transcript) {
        // cdsPosition might need adjusting for transcripts with unclear start
        // Found GRCh38 transcript which does not have the unconfirmed start flag BUT the first aa is an X;
        // ENST00000618610 (ENSP00000484524)
        if (transcript.unconfirmedStart() || (transcript.getProteinSequence() != null
                && transcript.getProteinSequence().startsWith(UNKNOWN_AMINOACID))) {
            int firstCodingExonPhase = getFirstCodingExonPhase(transcript);
            // firstCodingExonPhase is the ENSEMBL's annotated phase for the transcript, which takes following values
            // - 0 if fits perfectly with the reading frame, i.e.
            // Sequence ---------ACTTACGGTC
            // Codons            ---|||---|||
            // - 1 if shifted one position, i.e.
            // Sequence ---------ACTTACGGTC
            // Codons             ---|||---||||
            // - 2 if shifted two positions, i.e.
            // Sequence ---------ACTTACGGTC
            // Codons              ---|||---|||
            if (firstCodingExonPhase != -1) {
                //cdsPosition += (3 - firstCodingExonPhase) % 3;
                cdsPosition -= firstCodingExonPhase;
            }
        }

        return ((cdsPosition - 1) / 3) + 1;
    }

    protected static int getCdnaCodingStart(Transcript transcript) {
        int cdnaCodingStart = transcript.getCdnaCodingStart();
        if (transcript.unconfirmedStart()) {
            //cdnaCodingStart -= ((3 - getFirstCdsPhase(transcript)) % 3);
            cdnaCodingStart += getFirstCodingExonPhase(transcript);
        }
        return cdnaCodingStart;
    }

    protected static int getFirstCodingExonPhase(Transcript transcript) {
        // Assuming exons are ordered
        for (Exon exon : transcript.getExons()) {
            if (exon.getPhase() != -1) {
                return exon.getPhase();
            }
        }

        return -1;
    }

    protected static int getPhaseShift(int cdsPosition, Transcript transcript) {
        // phase might need adjusting for transcripts with unclear start
        // Found GRCh38 transcript which does not have the unconfirmed start flag BUT the first aa is an X;
        // ENST00000618610 (ENSP00000484524)
        if (transcript.unconfirmedStart() || transcript.getProteinSequence().startsWith(UNKNOWN_AMINOACID)) {
            int firstCodingExonPhase = getFirstCodingExonPhase(transcript);
            // firstCodingExonPhase is the ENSEMBL's annotated phase for the transcript, which takes following values
            // - 0 if fits perfectly with the reading frame, i.e.
            // Sequence ---------ACTTACGGTC
            // Codons            ---|||---|||
            // - 1 if shifted one position, i.e.
            // Sequence ---------ACTTACGGTC
            // Codons             ---|||---||||
            // - 2 if shifted two positions, i.e.
            // Sequence ---------ACTTACGGTC
            // Codons              ---|||---|||
            if (firstCodingExonPhase != -1) {
                //cdsPosition += (3 - firstCodingExonPhase) % 3;
                //return (cdsPosition - 1) % 3;
                cdsPosition -= firstCodingExonPhase;
            }
        }

        return (cdsPosition - 1) % 3;
    }

    protected String formatCdnaCoords(BuildingComponents buildingComponents) {
        return null;
    }

    /**
     * Generate HGVS trancript/geneId prefix.
     * @param buildingComponents BuildingComponents object containing all elements needed to build the hgvs string
     * Some examples of full hgvs names with transcriptId include:
     * NM_007294.3:c.2207A>C
     * NM_007294.3(BRCA1):c.2207A>C
     */
    private String formatPrefix(BuildingComponents buildingComponents) {
        StringBuilder stringBuilder = new StringBuilder(buildingComponents.getTranscriptId());
        stringBuilder.append("(").append(buildingComponents.getGeneId()).append(")");

        return stringBuilder.toString();
    }

    /**
     * Required due to the peculiarities of insertion coordinates.
     * Translation to transcript cds position slightly varies for positive and negative strands because of the way
     * the insertion coordinates are interpreted in the genomic context; imagine following GENOMIC variant:
     * 7:-:GTATCCA
     * and following GENOMIC sequence
     * COORDINATES                             123456789 10 11 12 13 14 15 16 17 18 19 20
     * GENOMIC SEQUENCE                        AAGACTGTA T  C  C  A  G  G  T  G  G  G  C
     * ORF(bars indicate last nt of the codon)   |  |  |       |        |        |
     * VARIANT                                       ^
     * VARIANT                                       GTATCCA
     *
     * In a positive transcript shifted codon is GTA (genomic positions [7,9])
     * In a negative transcript shifted codon is AGT (reverse complementary of ACT, genomic positions [4,6]) and
     * therefore the cds start coordinate of the insertion must be +1
     * @param transcript  affected transcript data
     * @param genomicStart start genomic coordinate of the variant
     * @return corresponding cds start coordinate appropriately adjusted according to the transcript strand
     */
    protected static int getCdsStart(Transcript transcript, int genomicStart) {
        return POSITIVE.equals(transcript.getStrand())
                ? genomicToCdnaCoord(transcript, genomicStart).getReferencePosition()
                : genomicToCdnaCoord(transcript, genomicStart).getReferencePosition() + 1;
    }

}
