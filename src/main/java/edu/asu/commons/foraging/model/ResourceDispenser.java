package edu.asu.commons.foraging.model;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.logging.Logger;

import edu.asu.commons.foraging.conf.RoundConfiguration;
import edu.asu.commons.foraging.event.ResetTokenDistributionRequest;

/**
 * $Id$
 *
 * Creates resource tokens in the game world. Current implementation generates a
 * resource token probabilistically. The probability that a token will be generated
 * in an empty space is
 * P(t) = r * the number of neighboring cells containing a resource token / totalNumberOfNeighboringCells
 * 
 * @version $Revision$
 */

public class ResourceDispenser {

    private final static Logger logger = Logger.getLogger(ResourceDispenser.class.getName());

    private final static Map<String, Type> resourceGeneratorTypeMap = new HashMap<String, Type>(3);

    public enum Type {
        NEIGHBORHOOD_DENSITY_DEPENDENT("neighborhood-density-dependent"),
        TOP_BOTTOM_PATCHY("top-bottom-patchy"),
        MOBILE("mobile"),
        DENSITY_DEPENDENT("density-dependent");

        final String name;

        private Type(String name) {
            this.name = name;
            resourceGeneratorTypeMap.put(name, this);
        }

        public static Type find(final String name) {
            Type type = resourceGeneratorTypeMap.get(name);
            if (type == null) {
                type = valueOf(name);
                if (type == null) {
                    // FIXME: default value is density-dependent
                    logger.warning("Couldn't find resource generator by name, returning default: " + name);
                    type = NEIGHBORHOOD_DENSITY_DEPENDENT;
                }
            }
            return type;
        }

        public String toString() {
            return name;
        }
    }

    private final ServerDataModel serverDataModel;

    private final Random random = new Random();
    // FIXME: turn these into factory driven based on configuration parameter.
    private ResourceGenerator currentResourceGenerator;

    private final DensityDependentResourceGenerator densityDependentGenerator =
            new DensityDependentResourceGenerator();
    private final StochasticGenerator neighborhoodDensityDependentGenerator =
            new NeighborhoodDensityDependentResourceGenerator();
    private final TopBottomPatchGenerator topBottomPatchGenerator = new TopBottomPatchGenerator();
    private final MobileResourceGenerator mobileResourceGenerator = new MobileResourceGenerator();

    // FIXME: refactor this so that there's a single generator/strategy that gets used per round?
    // private Generator resourceGenerator;

    public ResourceDispenser(final ServerDataModel serverDataModel) {
        this.serverDataModel = serverDataModel;
    }

    public void resetTokenDistribution(ResetTokenDistributionRequest event) {
        if (serverDataModel.getRoundConfiguration().isPracticeRound()) {
            GroupDataModel group = serverDataModel.getGroup(event.getId());
            group.resetResourceDistribution();
            // FIXME: won't work if practice round is patchy
            Set<Resource> resources = currentResourceGenerator.generateInitialDistribution(group);
            serverDataModel.addResources(group, resources);
        }
    }

    public void initialize() {
        initialize(serverDataModel.getRoundConfiguration());
    }

    public void initialize(RoundConfiguration roundConfiguration) {
        ResourceDispenser.Type resourceGeneratorType = ResourceDispenser.Type.find(roundConfiguration.getResourceGeneratorType());
        currentResourceGenerator = getResourceGenerator(resourceGeneratorType);
        currentResourceGenerator.initialize(roundConfiguration);
    }

    protected ResourceGenerator getResourceGenerator(Type resourceGeneratorType) {
        switch (resourceGeneratorType) {
//            case DENSITY_DEPENDENT:
//                return densityDependentGenerator;
            case NEIGHBORHOOD_DENSITY_DEPENDENT:
                return neighborhoodDensityDependentGenerator;
            case TOP_BOTTOM_PATCHY:
                return topBottomPatchGenerator;
            case MOBILE:
                return mobileResourceGenerator;
            default:
                return neighborhoodDensityDependentGenerator;
        }
    }

    @Deprecated
    public void updateResourceAge(GroupDataModel group) {
        for (Resource resource : group.getResourceDistribution().values()) {
            // FIXME: needs to be modded to wraparound.
            resource.setAge(resource.getAge() + 1);
        }
    }

    public Map<GroupDataModel, Set<Resource>> generateResources() {
        return generateResources(getCurrentResourceGenerator());
    }

    public Map<GroupDataModel, Set<Resource>> generateResources(ResourceGenerator generator) {
        Map<GroupDataModel, Set<Resource>> map = new HashMap<>();
        for (GroupDataModel group : serverDataModel.getGroups()) {
            map.put(group, generator.generate(group));
        }
        return map;
    }

    public ResourceGenerator getCurrentResourceGenerator() {
        return currentResourceGenerator;
    }

    public class MobileResourceGenerator extends ResourceGenerator.Base {
        private double tokenMovementProbability;
        private double tokenBirthProbability;

        public void initialize(RoundConfiguration roundConfiguration) {
            this.tokenMovementProbability = roundConfiguration.getTokenMovementProbability();
            this.tokenBirthProbability = roundConfiguration.getTokenBirthProbability();
            for (GroupDataModel group : serverDataModel.getGroups()) {
                Set<Resource> resources = generateInitialDistribution(group);
                serverDataModel.addResources(group, resources);
            }
        }

        /**
         * Moves all resources one-at-a-time. Moved resources need to be aware of the updated
         * resource positions, otherwise resources could "disappear".
         * XXX: could optimize by generating a list of all removed resources and then a list of all added resources
         * and then first removing all resources and then adding new resources.
         * 
         * @param group
         */
        public Set<Resource> generate(GroupDataModel group) {
            // getResourcePositions() returns a new HashSet
            // this Set will contain the most up-to-date resource positions as a working copy.
            final Set<Point> currentResourcePositions = group.getResourcePositions();
            final Set<Point> addedResources = new HashSet<>();
            final Set<Point> removedResources = new HashSet<>();
            // iterate over a copy so we can update currentResourcePositions.
            // we need to update them one-at-a-time, otherwise a resource might move to a location that
            // has already been moved to...
            final List<Point> shuffledCopy = new ArrayList<>(currentResourcePositions);
            Collections.shuffle(shuffledCopy);
            // iterate through a new randomized copy of the points
            for (Point currentResourcePosition : shuffledCopy) {
                if (random.nextDouble() < tokenMovementProbability) {
                    // this token is ready to move.
                    final List<Point> validNeighbors = getValidMooreNeighborhood(currentResourcePosition, currentResourcePositions);
                    if (validNeighbors.isEmpty()) {
                        // this point can't move anywhere.
                        continue;
                    }
                    final Point newPosition = validNeighbors.get(random.nextInt(validNeighbors.size()));
                    // either execute one move at a time or execute a bulk move.
                    addedResources.add(newPosition);
                    removedResources.add(currentResourcePosition);
                    // serverDataModel.moveResource(group, currentResourcePosition, newPosition);
                    currentResourcePositions.remove(currentResourcePosition);
                    currentResourcePositions.add(newPosition);
                    // newResources.add(new Resource(newPosition));
                    // removedResources.add(currentResourcePosition);
                }
            }
            serverDataModel.moveResources(group, removedResources, addedResources);
            shuffledCopy.clear();
            shuffledCopy.addAll(currentResourcePositions);
            Collections.shuffle(shuffledCopy);
            Set<Resource> addedOffspring = new HashSet<>();
            // next, generate offspring.
            // use current resource positions.
            for (Point currentResourcePosition : currentResourcePositions) {
                if (random.nextDouble() < tokenBirthProbability) {
                    final List<Point> validNeighbors = getValidMooreNeighborhood(currentResourcePosition, currentResourcePositions);
                    if (validNeighbors.isEmpty()) {
                        // cannot generate offspring anywhere, is resource-locked.
                        continue;
                    }
                    final Point offspringPosition = validNeighbors.get(random.nextInt(validNeighbors.size()));
                    addedOffspring.add(new Resource(offspringPosition));
                }
            }
            serverDataModel.addResources(group, addedOffspring);
            return addedOffspring;
        }

        private List<Point> getValidMooreNeighborhood(Point referencePoint, Set<Point> existingPositions) {
            List<Point> neighborhoodPoints = new ArrayList<>();
            int currentX = referencePoint.x;
            int currentY = referencePoint.y;
            int endX = currentX + 2;
            int endY = currentY + 2;
            for (int x = currentX - 1; x < endX; x++) {
                for (int y = currentY - 1; y < endY; y++) {
                    Point point = new Point(x, y);
                    // only add a point to the neighborhood set if it doesn't already have a resource.
                    if (serverDataModel.isValidPosition(point) && !existingPositions.contains(point)) {
                        neighborhoodPoints.add(point);
                    }
                }
            }
            return neighborhoodPoints;
        }
    }

    public class TopBottomPatchGenerator extends NeighborhoodDensityDependentResourceGenerator {

        private double topRate;
        private double bottomRate;
        private double topDistribution;
        private double bottomDistribution;

        public void setBottomDistribution(double bottomDistribution) {
            this.bottomDistribution = bottomDistribution;
        }

        public void setTopDistribution(double topDistribution) {
            this.topDistribution = topDistribution;
        }

        public void setBottomRate(double bottomRate) {
            this.bottomRate = bottomRate;
        }

        public void setTopRate(double topRate) {
            this.topRate = topRate;
        }

        public void initialize(RoundConfiguration configuration) {
            setBottomDistribution(configuration.getBottomInitialResourceDistribution());
            setBottomRate(configuration.getBottomRegrowthScalingFactor());
            setTopDistribution(configuration.getTopInitialResourceDistribution());
            setTopRate(configuration.getTopRegrowthScalingFactor());
            for (GroupDataModel group : serverDataModel.getGroups()) {
                Set<Resource> resources = generateInitialDistribution(group);
                serverDataModel.addResources(group, resources);
            }
        }

        /**
         * Generates an initial distribution for top/bottom patches based on the top/bottom initial distribution
         * configuration parameters.
         */
        @Override
        public Set<Resource> generateInitialDistribution(GroupDataModel group) {
            int width = serverDataModel.getBoardWidth();
            int height = serverDataModel.getBoardHeight() / 2;
            int topTokensNeeded = (int) (width * height * topDistribution);
            logger.info("number of tokens needed on top: " + topTokensNeeded);
            Set<Resource> newResources = new HashSet<Resource>();
            while (newResources.size() < topTokensNeeded) {
                Point point = new Point(random.nextInt(width), random.nextInt(height));
                newResources.add(new Resource(point));
            }
            int bottomTokensNeeded = (int) (width * height * bottomDistribution);
            logger.info("number of tokens needed on bottom:" + bottomTokensNeeded);
            int tokensNeeded = topTokensNeeded + bottomTokensNeeded;
            while (newResources.size() < tokensNeeded) {
                Point point = new Point(random.nextInt(width), random.nextInt(height) + height);
                newResources.add(new Resource(point));
            }
            return newResources;
        }

        @Override
        public Set<Resource> generate(GroupDataModel group) {
            // partition the grid into north and south halves.
            // regenerate food for the top half.
            int divisionPoint = serverDataModel.getBoardHeight() / 2;
            Set<Resource> newResources = new HashSet<>();
            for (int y = 0; y < divisionPoint; y++) {
                for (int x = 0; x < serverDataModel.getBoardWidth(); x++) {
                    Point currentPoint = new Point(x, y);
                    if (!group.isResourceAt(currentPoint)) {
                        if (random.nextDouble() < getProbabilityForCell(group, x, y, topRate)) {
                            newResources.add(new Resource(currentPoint));
                        }
                    }
                }
            }
            // regenerate food for the bottom half
            for (int y = divisionPoint; y < serverDataModel.getBoardHeight(); y++) {
                for (int x = 0; x < serverDataModel.getBoardWidth(); x++) {
                    Point currentPoint = new Point(x, y);
                    if (!group.isResourceAt(currentPoint)) {
                        if (random.nextDouble() < getProbabilityForCell(group, x, y, bottomRate)) {
                            newResources.add(new Resource(currentPoint));
                        }
                    }
                }
            }
            // add all resources to the server
            serverDataModel.addResources(group, newResources);
            return newResources;
        }

        @Override
        public double getProbabilityForCell(GroupDataModel group, int x, int y) {
            return getProbabilityForCell(group, x, y,
                    (y < serverDataModel.getBoardHeight() / 2) ? topRate : bottomRate);
        }

    }

    /**
     * Density dependent resource regeneration that is not dependent on neighboring cells, but overall state of the resource.
     * 
     * raw_regrowth = number of remaining tokens * regrowth rate
     * if raw regrowth > 1, return raw regrowth * (number of open cells / total cells), clamped to 1
     * if raw regrowth is between 0 and 1 because resource distribution size is between 1 and 10, generate a uniformly distributed
     * random number that must be <= raw regrowth.
     */
    public class DensityDependentResourceGenerator extends ResourceGenerator.Base {
        private double regrowthRate;

        public void initialize(RoundConfiguration roundConfiguration) {
            regrowthRate = roundConfiguration.getRegrowthRate();
            for (GroupDataModel group : serverDataModel.getGroups()) {
                Set<Resource> resources = generateInitialDistribution(group);
                serverDataModel.addResources(group, resources);
            }
        }

        @Override
        public Set<Resource> generate(GroupDataModel group) {
            Set<Resource> newResources = new HashSet<>();
            Map<Point, Resource> resourceDistribution = group.getResourceDistribution();
            int totalNumberOfResources = resourceDistribution.size();
            int totalNumberOfCells = serverDataModel.getBoardHeight() * serverDataModel.getBoardWidth();
            double rawRegrowth = totalNumberOfResources * regrowthRate;
            int regrowth = 0;
            logger.info("Raw regrowth: " + rawRegrowth);
            if (rawRegrowth > 1) {
                double availableCellsRatio = totalNumberOfResources / (double) totalNumberOfCells;
                regrowth = Math.max((int) Math.round(rawRegrowth * availableCellsRatio), 1);
            }
            else if (random.nextDouble() <= rawRegrowth) {
                regrowth = 1;
            }
            logger.info("Regrowth: " + regrowth);
            if (regrowth > 0) {
                ArrayList<Point> availableLocations = new ArrayList<>();
                for (int i = 0; i < serverDataModel.getBoardHeight(); i++) {
                    for (int j = 0; j < serverDataModel.getBoardWidth(); j++) {
                        availableLocations.add(new Point(i, j));
                    }
                }
                availableLocations.remove(resourceDistribution.keySet());
                Collections.shuffle(availableLocations);
                for (Point point : availableLocations.subList(0, regrowth)) {
                    newResources.add(new Resource(point, 1));
                }
                serverDataModel.addResources(group, newResources);
            }
            return newResources;
        }

    }

    /**
     * Algorithm:
     * 1. for each cell in the grid, calculate ratio of token-occupied neighboring cells
     * to max number of cells
     * 2. multiply ratio by regrowth rate configuration parameter
     * 3. if result > random.nextDouble(), add token to that grid cell.
     */
    public class NeighborhoodDensityDependentResourceGenerator extends ResourceGenerator.Base implements StochasticGenerator {
        private double rate;

        public void initialize(RoundConfiguration roundConfiguration) {
            this.rate = roundConfiguration.getRegrowthRate();
            for (GroupDataModel group : serverDataModel.getGroups()) {
                Set<Resource> resources = generateInitialDistribution(group);
                logger.info("density dependent resource generator initialized with " + resources.size() + " resources.");
                serverDataModel.addResources(group, resources);
            }
        }

        public double getProbabilityForCell(GroupDataModel group, int currentX, int currentY) {
            return getProbabilityForCell(group, currentX, currentY, rate);
        }

        protected double getProbabilityForCell(GroupDataModel group, int currentX, int currentY, double rate) {
            return rate * getNeighborsTokenRatio(group, currentX, currentY);
        }

        protected double getNeighborsTokenRatio(final GroupDataModel group, final int currentX, final int currentY) {
            double neighborsWithTokens = 0;
            // start off at -1 to offset the off-by-one we get from adding the
            // current cell.
            double maxNeighbors = -1;
            // use the Moore neighborhood (all 8 cells surrounding the empty cell).
            for (int x = currentX - 1; x < currentX + 2; x++) {
                for (int y = currentY - 1; y < currentY + 2; y++) {
                    Point neighbor = new Point(x, y);
                    // FIXME: if we ever decide to have Group-specific boundaries/territorial
                    // sizes, then we will need to change this.
                    if (serverDataModel.isValidPosition(neighbor)) {
                        maxNeighbors++;
                        if (group.isResourceAt(neighbor)) {
                            neighborsWithTokens++;
                        }
                    }
                }
            }
            // logger.info(String.format("[%f / % f] = %f", neighborsWithFood,
            // maxNeighbors, neighborsWithFood / maxNeighbors));
            return neighborsWithTokens / maxNeighbors;
        }

        // FIXME: can make this algorithm more efficient. Instead of scanning
        // across the entire grid, look at existing set of Food points and check
        // their neighbor probabilities
        public Set<Resource> generate(GroupDataModel group) {
            // FIXME: extremely important - add to a scratch space first and then copy over all at once to avoid copy problem.
            Set<Resource> newResources = new HashSet<>();
            for (int y = 0; y < serverDataModel.getBoardHeight(); y++) {
                for (int x = 0; x < serverDataModel.getBoardWidth(); x++) {
                    Point currentPoint = new Point(x, y);
                    if (! group.isResourceAt(currentPoint)) {
                        random.doubles();
                        if (random.nextDouble() < getProbabilityForCell(group, x, y)) {
                            // FIXME: should initial age be parameterizable?
                            newResources.add(new Resource(currentPoint, 1));
                        }
                    }
                }
            }
            serverDataModel.addResources(group, newResources);
            return newResources;
        }
    }

    public StochasticGenerator getDensityDependentGenerator() {
        return neighborhoodDensityDependentGenerator;
    }

    public TopBottomPatchGenerator getTopBottomPatchGenerator() {
        return topBottomPatchGenerator;
    }

    public MobileResourceGenerator getMobileResourceGenerator() {
        return mobileResourceGenerator;
    }

}
