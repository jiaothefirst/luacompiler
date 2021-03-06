package com.jdy.lua.lobjects;

import com.jdy.lua.LuaConstants;
import lombok.Data;

import java.util.*;

/**
 * 仅用 Map 实现表
 */
@Data
public class Table extends GcObject {

    Map<TValue,TValue> node = new HashMap<>();
    Table metatable;
    List<GcObject> gcList = new ArrayList<>();



    public TValue get(TValue key){
       return node.get(key);
    }
    public void put(TValue key,TValue value){
        node.put(key,value);
    }

    public boolean hasMetaMethod(String metaName){
        if(metatable != null){
            return metatable.get(createStringKey(metaName)) != null;
        }
        return false;
    }


    @Data
    public static class TableKey{
        int key_tt;
        Value value;

        public TableKey(int key_tt, Value value) {
            this.key_tt = key_tt;
            this.value = value;
        }
        public TableKey(){

        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            TableKey tableKey = (TableKey) o;
            return key_tt == tableKey.key_tt &&
                    value.equals(tableKey.value);
        }

        @Override
        public int hashCode() {
            return Objects.hash(key_tt, value);
        }
    }

    public static TValue createStringKey(String key){
        TValue v = new TValue();
        v.setValueType(LuaConstants.LUA_TSTRING);
        v.setObj(key);
        return v;
    }
}
