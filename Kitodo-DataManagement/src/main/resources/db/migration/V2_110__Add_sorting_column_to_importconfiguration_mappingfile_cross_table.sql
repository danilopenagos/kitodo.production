--
-- (c) Kitodo. Key to digital objects e. V. <contact@kitodo.org>
--
-- This file is part of the Kitodo project.
--
-- It is licensed under GNU General Public License version 3 or later.
--
-- For the full copyright and license information, please read the
-- GPL3-License.txt file that was distributed with this source code.
--
-- Migration: Add 'sorting' column to importconfiguration/mappingfile cross table
ALTER TABLE importconfiguration_x_mappingfile ADD sorting INT(11) DEFAULT 0;
