package com.treelang.films;

/**
 * Immutable class representing a city with its name and code.
 */
public class City {
    private final String regionName;
    private final int cityCode;

    /**
     * Creates a new City with the specified name and code.
     *
     * @param regionName the name of the city
     * @param cityCode the city code
     */
    public City(String regionName, int cityCode) {
        this.regionName = regionName;
        this.cityCode = cityCode;
    }

    /**
     * Gets the region name of this city.
     *
     * @return the region name
     */
    public String getRegionName() {
        return regionName;
    }

    /**
     * Gets the city code.
     *
     * @return the city code
     */
    public int getCityCode() {
        return cityCode;
    }
}
